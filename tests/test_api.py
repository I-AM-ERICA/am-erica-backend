import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

import httpx
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.assistants.policy import PolicyRetriever
from app.auth import Identity
from app.config import Settings
from app.integrations.llm import LanguageModel
from app.integrations.supabase import SupabaseGateway
from app.jobs import LocalJobQueue
from app.schemas import AssistantId, AssistantRequest, AssistantResponse, Principal
from main import create_app

PROFILE = {'name': '테스트 가게', 'owner': '사장', 'category': '카페', 'region': '서울'}


def payload(message='신메뉴 홍보를 도와주세요.', format='카드뉴스 문안'):
    return {'message': message, 'format': format, 'profile': PROFILE}


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(create_app(Settings(_env_file=None)))
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def test_startup_and_docs(self):
        self.assertEqual(self.client.get('/').status_code, 200)
        self.assertEqual(self.client.get('/docs').status_code, 200)
        self.assertEqual(self.client.get('/api/health').json()['status'], 'ok')
        self.assertEqual(len(self.client.get('/api/assistants').json()), 4)

    def test_all_frontend_endpoints(self):
        for assistant in ('policy', 'marketing', 'customer', 'sales'):
            data = payload('매출\n1000' if assistant == 'sales' else '운영자금이 필요해요.')
            result = self.client.post(f'/api/assistants/{assistant}', json=data)
            self.assertEqual(result.status_code, 200)
            self.assertIn('text', result.json())
            self.assertIsInstance(result.json()['sources'], list)

    def test_sales_totals_refunds_and_peak(self):
        result = self.client.post('/api/assistants/sales', json=payload('\ufeff날짜,시간,매출\r\n2026-11-01,10,"10,000"\r\n2026-11-01,12,30000\r\n2026-11-02,12,20000\r\n2026-11-02,15,-5000')).json()
        self.assertEqual(result['mode'], 'calculated')
        self.assertIn('총매출: 55,000원', result['text'])
        self.assertIn('일평균 매출: 27,500원', result['text'])
        self.assertIn('12시 (50,000원)', result['text'])

    def test_invalid_csv_is_not_silently_accepted(self):
        for csv in ('매출\nabc', '매출\nNaN', '매출\nInfinity', '날짜\n2026-11-01', '매출', '시간,매출\n25,100', '매출,메모\n100', '매출\n"100'):
            self.assertEqual(self.client.post('/api/assistants/sales', json=payload(csv)).status_code, 422, csv)

    def test_invalid_requests(self):
        for data in ({}, payload(' '), payload('x' * 20001)):
            self.assertEqual(self.client.post('/api/assistants/policy', json=data).status_code, 422)
        self.assertEqual(self.client.post('/api/assistants/unknown', json=payload()).status_code, 422)

    def test_policy_has_no_fabricated_sources(self):
        result = self.client.post('/api/assistants/policy', json=payload('지원금')).json()
        self.assertEqual(result['sources'], [])
        self.assertIn('찾지 못했습니다', result['text'])

    def test_cors_allows_frontend_and_denies_unknown_origin(self):
        for origin, allowed in [('http://localhost:7001', True), ('https://unknown.example', False)]:
            response = self.client.options('/api/assistants/policy', headers={'Origin': origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type'})
            self.assertEqual('access-control-allow-origin' in response.headers, allowed)

    def test_history_requires_configuration(self):
        self.assertEqual(self.client.get('/api/history').status_code, 503)

    def test_background_worker_completes(self):
        response = self.client.post('/api/jobs/marketing', json=payload())
        self.assertEqual(response.status_code, 202)
        job_id = response.json()['id']
        # 별도 조회 요청을 통해 워커의 완료 상태를 확인합니다.
        for _ in range(100):
            job = self.client.get(f'/api/jobs/{job_id}').json()
            if job['status'] == 'completed':
                break
        self.assertEqual(job['status'], 'completed')
        self.assertIn('text', job['result'])
        self.assertEqual(self.client.get('/api/jobs/missing').status_code, 404)

    def test_supabase_mode_requires_login(self):
        settings = Settings(_env_file=None, auth_mode='supabase', supabase_url='https://project.example', supabase_publishable_key='publishable-test')
        with TestClient(create_app(settings)) as client:
            self.assertEqual(client.get('/api/auth/me').status_code, 401)
            self.assertEqual(client.post('/api/assistants/customer', json=payload()).status_code, 401)


class AsyncIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def test_hyperclova_wire_format(self):
        def handle(request):
            self.assertEqual(request.url.path, '/v3/chat-completions/HCX-005')
            self.assertEqual(request.headers['Authorization'], 'Bearer fake-key')
            return httpx.Response(200, json={'status': {'code': '20000'}, 'result': {'message': {'content': 'AI 답변'}}})
        settings = Settings(_env_file=None, llm_provider='hyperclova', clova_api_key='fake-key')
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            self.assertEqual(await LanguageModel(settings, client).generate('지시문', '요청'), 'AI 답변')

    async def test_provider_failure_does_not_return_demo_or_secrets(self):
        settings = Settings(_env_file=None, llm_provider='hyperclova', clova_api_key='fake-key')
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(403, text='fake-key'))) as client:
            with self.assertRaises(HTTPException) as caught:
                await LanguageModel(settings, client).generate('지시문', '요청')
            self.assertEqual(caught.exception.status_code, 502)
            self.assertNotIn('fake-key', caught.exception.detail)

    async def test_supabase_verifies_token_with_auth_server(self):
        uid = '11111111-1111-4111-8111-111111111111'
        def handle(request):
            self.assertEqual(request.url.path, '/auth/v1/user')
            self.assertEqual(request.headers['apikey'], 'public-key')
            self.assertEqual(request.headers['Authorization'], 'Bearer user-token')
            return httpx.Response(200, json={'id': uid, 'email': 'test@example.com'})
        settings = Settings(_env_file=None, auth_mode='supabase', supabase_url='https://project.example', supabase_publishable_key='public-key')
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            self.assertEqual((await SupabaseGateway(settings, client).verify_user('user-token')).id, uid)

    async def test_jobs_are_owned_and_queue_is_bounded(self):
        queue = LocalJobQueue(AsyncMock(return_value=AssistantResponse(text='완료', mode='demo')), capacity=1)
        owner = Identity(Principal(id='owner'))
        job = queue.submit(AssistantId.marketing, AssistantRequest(**payload()), owner)
        with self.assertRaises(HTTPException) as caught:
            queue.get(job.id, Identity(Principal(id='other')))
        self.assertEqual(caught.exception.status_code, 404)
        with self.assertRaises(HTTPException) as caught:
            queue.submit(AssistantId.marketing, AssistantRequest(**payload()), owner)
        self.assertEqual(caught.exception.status_code, 429)
        queue.start()
        await asyncio.wait_for(queue.queue.join(), timeout=2)
        self.assertEqual(queue.get(job.id, owner).status, 'completed')
        await queue.stop()


class RetrievalTests(unittest.TestCase):
    def test_search_returns_only_matching_registered_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'policies.json'
            path.write_text('[{"title":"운영자금 안내","url":"https://example.com/policy","content":"카페 운영자금 문서 테스트"}]', encoding='utf-8')
            retriever = PolicyRetriever(path)
            self.assertEqual(len(retriever.retrieve('운영자금')), 1)
            self.assertEqual(retriever.retrieve('abcdef'), [])


if __name__ == '__main__':
    unittest.main()

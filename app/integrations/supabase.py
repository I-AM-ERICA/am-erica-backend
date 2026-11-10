from uuid import UUID

import httpx
from fastapi import HTTPException

from app.config import Settings
from app.schemas import AssistantId, AssistantRequest, AssistantResponse, Principal


class SupabaseGateway:
    def __init__(self, settings: Settings, client: httpx.AsyncClient):
        self.settings, self.client = settings, client

    def headers(self, token: str) -> dict[str, str]:
        return {'apikey': self.settings.supabase_publishable_key.get_secret_value(), 'Authorization': f'Bearer {token}'}

    async def verify_user(self, token: str) -> Principal:
        try:
            response = await self.client.get(f'{self.settings.supabase_url.rstrip("/")}/auth/v1/user', headers=self.headers(token))
            if response.status_code in (401, 403):
                raise HTTPException(401, '로그인이 만료됐거나 올바르지 않습니다.', headers={'WWW-Authenticate': 'Bearer'})
            response.raise_for_status()
            data = response.json()
            return Principal(id=str(UUID(data['id'])), email=data.get('email'))
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise HTTPException(503, '인증 서비스를 확인할 수 없습니다.') from exc

    async def save_history(self, owner: Principal, token: str, assistant: AssistantId, request: AssistantRequest, result: AssistantResponse):
        payload = {'user_id': owner.id, 'assistant': assistant.value, 'title': request.message[:80] if assistant != AssistantId.sales else '매출 데이터 분석', 'format': request.format, 'profile': request.profile.model_dump(), 'result': result.model_dump(mode='json')}
        try:
            response = await self.client.post(f'{self.settings.supabase_url.rstrip("/")}/rest/v1/assistant_history', headers={**self.headers(token), 'Prefer': 'return=minimal'}, json=payload)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise HTTPException(503, '작업 결과를 데이터베이스에 저장하지 못했습니다.') from exc

    async def list_history(self, token: str, limit: int):
        try:
            response = await self.client.get(f'{self.settings.supabase_url.rstrip("/")}/rest/v1/assistant_history', headers=self.headers(token), params={'select': '*', 'order': 'created_at.desc', 'limit': limit})
            response.raise_for_status()
            return response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise HTTPException(503, '작업 내역을 불러오지 못했습니다.') from exc

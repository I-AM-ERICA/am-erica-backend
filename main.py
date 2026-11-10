from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware

from app.assistants.policy import PolicyRetriever
from app.auth import Identity, current_identity
from app.config import Settings
from app.integrations.llm import LanguageModel
from app.integrations.supabase import SupabaseGateway
from app.jobs import LocalJobQueue
from app.orchestrator import Orchestrator
from app.schemas import AssistantId, AssistantRequest, AssistantResponse, Job, Principal


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        async with httpx.AsyncClient(timeout=25.0, follow_redirects=False) as client:
            application.state.settings = settings
            application.state.supabase = SupabaseGateway(settings, client)
            application.state.orchestrator = Orchestrator(settings, LanguageModel(settings, client), PolicyRetriever(settings.policy_documents_path))

            async def execute(assistant: AssistantId, payload: AssistantRequest, identity: Identity):
                result = await application.state.orchestrator.run(assistant, payload)
                if settings.persist_history:
                    await application.state.supabase.save_history(identity.user, identity.token, assistant, payload, result)
                return result

            application.state.execute = execute
            application.state.jobs = LocalJobQueue(execute)
            application.state.jobs.start()
            try:
                yield
            finally:
                await application.state.jobs.stop()

    application = FastAPI(title='가게비서 API', version='0.1.0', description='정책·홍보·고객응대·경영 비서를 연결하는 공통 FastAPI 백엔드', lifespan=lifespan)
    application.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins, allow_credentials=False, allow_methods=['GET', 'POST', 'OPTIONS'], allow_headers=['Content-Type', 'Authorization'])

    @application.get('/', tags=['안내'])
    async def root():
        return {'service': '가게비서 API', 'docs': '/docs', 'health': '/api/health'}

    @application.get('/api/health', tags=['안내'])
    async def health():
        return {'status': 'ok', 'llm_provider': settings.llm_provider, 'auth_mode': settings.auth_mode, 'persist_history': settings.persist_history, 'queue': 'local-in-memory', 'external_connections_verified': False}

    @application.get('/api/assistants', tags=['비서'])
    async def assistants():
        names = {'policy': '정책 비서', 'marketing': '홍보 비서', 'customer': '고객응대 비서', 'sales': '경영 비서'}
        return [{'id': assistant.value, 'name': names[assistant.value], 'endpoint': f'/api/assistants/{assistant.value}'} for assistant in AssistantId]

    @application.get('/api/auth/me', response_model=Principal, tags=['인증'])
    async def me(identity: Identity = Depends(current_identity)):
        return identity.user

    @application.post('/api/assistants/{assistant_id}', response_model=AssistantResponse, tags=['비서'])
    async def run_assistant(assistant_id: AssistantId, payload: AssistantRequest, request: Request, identity: Identity = Depends(current_identity)):
        return await request.app.state.execute(assistant_id, payload, identity)

    @application.post('/api/jobs/{assistant_id}', response_model=Job, status_code=202, tags=['비동기 작업'])
    async def submit_job(assistant_id: AssistantId, payload: AssistantRequest, request: Request, identity: Identity = Depends(current_identity)):
        return request.app.state.jobs.submit(assistant_id, payload, identity)

    @application.get('/api/jobs/{job_id}', response_model=Job, tags=['비동기 작업'])
    async def get_job(job_id: str, request: Request, identity: Identity = Depends(current_identity)):
        return request.app.state.jobs.get(job_id, identity)

    @application.get('/api/history', tags=['작업 내역'])
    async def history(request: Request, limit: int = Query(default=20, ge=1, le=100), identity: Identity = Depends(current_identity)):
        if not settings.persist_history:
            raise HTTPException(503, '서버 작업 내역 저장이 설정되지 않았습니다. 프론트엔드의 브라우저 작업 내역을 이용해 주세요.')
        return await request.app.state.supabase.list_history(identity.token, limit)

    return application


app = create_app()

if __name__ == '__main__':
    import uvicorn
    uvicorn.run('main:app', host='127.0.0.1', port=8000, reload=True)

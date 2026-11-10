from uuid import uuid4

import httpx
from fastapi import HTTPException

from app.config import Settings


class LanguageModel:
    def __init__(self, settings: Settings, client: httpx.AsyncClient):
        self.settings, self.client = settings, client

    async def generate(self, system: str, user: str) -> str:
        settings = self.settings
        messages = [{'role': 'system', 'content': system}, {'role': 'user', 'content': user}]
        if settings.llm_provider == 'hyperclova':
            url = f'{settings.clova_base_url.rstrip("/")}/v3/chat-completions/{settings.clova_model}'
            headers = {'Authorization': f'Bearer {settings.clova_api_key.get_secret_value()}', 'X-NCP-CLOVASTUDIO-REQUEST-ID': str(uuid4()), 'Accept': 'application/json'}
            payload = {'messages': messages, 'maxTokens': 1024, 'temperature': 0.5}
        elif settings.llm_provider == 'compatible':
            url = f'{settings.compatible_base_url.rstrip("/")}/chat/completions'
            headers = {'Authorization': f'Bearer {settings.compatible_api_key.get_secret_value()}'}
            payload = {'model': settings.compatible_model, 'messages': messages, 'max_tokens': 1024, 'temperature': 0.5}
        else:
            raise RuntimeError('체험 모드에서는 LLM을 호출하지 않습니다.')
        try:
            response = await self.client.post(url, headers=headers, json=payload)
            response.raise_for_status()
            data = response.json()
            if settings.llm_provider == 'hyperclova':
                if str(data.get('status', {}).get('code')) != '20000':
                    raise ValueError('모델 응답 오류')
                text = data['result']['message']['content']
            else:
                text = data['choices'][0]['message']['content']
            if not isinstance(text, str) or not text.strip():
                raise ValueError('비어 있는 모델 응답')
            return text
        except httpx.TimeoutException as exc:
            raise HTTPException(504, 'AI 응답 시간이 초과됐습니다.') from exc
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as exc:
            # 외부 응답에는 API 키나 개인정보가 포함될 수 있어 그대로 반환하지 않습니다.
            raise HTTPException(502, 'AI 서비스 연결 또는 응답에 문제가 있습니다.') from exc

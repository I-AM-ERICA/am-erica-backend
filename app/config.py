from pathlib import Path
from typing import Literal

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ROOT / '.env', env_file_encoding='utf-8', extra='ignore')

    llm_provider: Literal['demo', 'hyperclova', 'compatible'] = 'demo'
    auth_mode: Literal['local', 'supabase'] = 'local'
    persist_history: bool = False
    cors_origins: list[str] = ['http://localhost:7001', 'http://127.0.0.1:7001', 'http://localhost:7000', 'http://127.0.0.1:7000']
    clova_api_key: SecretStr = SecretStr('')
    clova_model: str = 'HCX-005'
    clova_base_url: str = 'https://clovastudio.stream.ntruss.com'
    compatible_api_key: SecretStr = SecretStr('')
    compatible_base_url: str = ''
    compatible_model: str = ''
    supabase_url: str = ''
    supabase_publishable_key: SecretStr = SecretStr('')
    policy_documents_path: Path = ROOT / 'data/policies.json'

    @model_validator(mode='after')
    def validate_connections(self):
        if not self.policy_documents_path.is_absolute():
            self.policy_documents_path = ROOT / self.policy_documents_path
        if self.llm_provider == 'hyperclova' and not self.clova_api_key.get_secret_value():
            raise ValueError('HyperCLOVA X 사용 시 CLOVA_API_KEY를 설정해 주세요.')
        if self.llm_provider == 'compatible' and not all([self.compatible_api_key.get_secret_value(), self.compatible_base_url, self.compatible_model]):
            raise ValueError('호환 LLM의 API 키, base URL, model을 설정해 주세요.')
        if self.auth_mode == 'supabase' and not all([self.supabase_url, self.supabase_publishable_key.get_secret_value()]):
            raise ValueError('Supabase URL과 publishable key를 설정해 주세요.')
        if self.persist_history and self.auth_mode != 'supabase':
            raise ValueError('작업 내역 영구 저장은 AUTH_MODE=supabase에서 사용해 주세요.')
        return self

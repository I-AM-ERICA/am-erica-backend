from datetime import datetime, timezone
from enum import Enum
from typing import Literal
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, HttpUrl


class AssistantId(str, Enum):
    policy = 'policy'
    marketing = 'marketing'
    customer = 'customer'
    sales = 'sales'


class StoreProfile(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    name: str = Field(min_length=1, max_length=100)
    owner: str = Field(min_length=1, max_length=100)
    category: str = Field(min_length=1, max_length=100)
    region: str = Field(min_length=1, max_length=100)


class AssistantRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    message: str = Field(min_length=1, max_length=1_000_000)
    format: str = Field(min_length=1, max_length=100)
    profile: StoreProfile


class Source(BaseModel):
    title: str
    url: HttpUrl


class AssistantResponse(BaseModel):
    text: str
    sources: list[Source] = Field(default_factory=list)
    mode: Literal['demo', 'ai', 'calculated', 'retrieved']


class Principal(BaseModel):
    id: str
    email: str | None = None


class Job(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid4()))
    assistant: AssistantId
    status: Literal['queued', 'running', 'completed', 'failed'] = 'queued'
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    result: AssistantResponse | None = None
    error: str | None = None

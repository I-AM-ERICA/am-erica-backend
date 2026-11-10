import json
import re
from pathlib import Path

from pydantic import BaseModel, HttpUrl

from app.schemas import Source


class PolicyDocument(BaseModel):
    title: str
    url: HttpUrl
    content: str


class PolicyRetriever:
    """검토된 로컬 문서를 검색하는 초기 RAG 검색기. 크롤링·Search-R1은 별도 연결합니다."""

    def __init__(self, path: Path):
        self.documents = [PolicyDocument.model_validate(item) for item in json.loads(path.read_text(encoding='utf-8'))]

    @staticmethod
    def terms(text: str) -> set[str]:
        # 한글 조사 차이를 줄이기 위한 문자 bigram과 단어 검색. 임베딩 검색은 아닙니다.
        words = re.findall(r'[가-힣a-zA-Z0-9]+', text.lower())
        return set(words) | {word[index:index + 2] for word in words for index in range(len(word) - 1)}

    def retrieve(self, query: str) -> list[PolicyDocument]:
        terms = self.terms(query)
        ranked = [(len(terms & self.terms(f'{doc.title} {doc.content}')), doc) for doc in self.documents]
        ranked.sort(key=lambda pair: pair[0], reverse=True)
        return [doc for score, doc in ranked[:3] if score > 0]

    @staticmethod
    def sources(documents: list[PolicyDocument]) -> list[Source]:
        return [Source(title=doc.title, url=doc.url) for doc in documents]

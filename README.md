# 가게비서 · am-erica-backend

React 프론트엔드의 정책·홍보·고객응대·경영 비서를 연결하는 FastAPI 백엔드입니다. Python 3.11 이상이 필요합니다.

## 바로 실행

이 컴퓨터에는 Python 3.7도 설치되어 있으므로 프로젝트의 Python 3.11 가상환경을 사용하세요.

```bash
cd ~/Desktop/am-erica-backend
source .venv/bin/activate
uvicorn main:app --reload
```

- 서버: http://127.0.0.1:8000
- Swagger API 문서: http://127.0.0.1:8000/docs
- 실행 상태: http://127.0.0.1:8000/api/health

포트 인자 없이 Uvicorn의 기본 8000번 포트를 사용합니다. `python main.py`로 실행해도 8000번에서 시작합니다. 종료는 Ctrl+C입니다.

다른 컴퓨터에서 처음 설치할 때:

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.lock
# 설정을 바꾸고 싶을 때만 .env.example을 .env로 복사합니다.
uvicorn main:app --reload
```

`requirements.lock`은 검증한 버전을 고정합니다. 의존성을 갱신하려면 `requirements.txt`의 버전 범위로 설치하고 다시 테스트하세요. 시스템 Python이나 전역 패키지는 변경하지 않습니다.

## 요청한 기술 구성

| 영역 | 현재 구현 | 추가 연결 사항 |
| --- | --- | --- |
| 공통 백엔드 | FastAPI, 입력 검증, CORS, API 문서 | 배포 환경 설정 |
| 비서 연결 | Python Orchestrator가 네 비서에 요청 분배 | 각 팀원의 전문 비서를 이 실행 경로에 연결 |
| 복잡한 워크플로 | 일반 Python으로 실행 | 분기·재시도가 복잡해질 때 LangGraph 도입 |
| DB | Supabase PostgreSQL REST 어댑터와 사용자별 RLS SQL | Supabase 프로젝트·테이블 설정 |
| 인증 | Supabase Auth 서버에서 access token 검증 | Supabase 설정 및 프론트 로그인 UI |
| 비동기 작업 | bounded asyncio Queue + 백그라운드 Worker | 운영용 Redis 등 외부 영속 큐 |
| AI 모델 | HyperCLOVA X 및 OpenAI 호환 API 어댑터 | 본인의 모델 API 키 |
| 정책 검색 | 등록한 JSON 문서의 키워드 검색과 RAG 컨텍스트 연결 | 실제 정책 원문·색인·최신성 관리, 필요 시 Search-R1 |

기본값은 `LLM_PROVIDER=demo`, `AUTH_MODE=local`, `PERSIST_HISTORY=false`입니다. **API 키나 DB가 없어도 실행됩니다.** 체험 모드는 템플릿을 반환하고, 매출은 CSV 실제 입력값을 계산합니다. 이미지·영상 파일 생성, 고객 답변 자동 발송, 실시간 식재료 시세, 실제 정책 수집, 로그인 UI는 포함하지 않습니다. LangGraph와 Search-R1은 설치하거나 실행하지 않았습니다.

## 프론트엔드 연결

기존 프론트엔드의 Vite 프록시가 `/api`를 `http://127.0.0.1:8000`으로 전달하도록 구성되어 있습니다. 프론트엔드 `.env.local`에 다음 값을 설정하고 Vite를 다시 시작하면 백엔드를 호출합니다.

```dotenv
VITE_API_MODE=live
```

백엔드는 7000·7001번 localhost/127.0.0.1 프론트엔드의 CORS 요청을 허용합니다. 프론트엔드의 `live`는 HTTP 호출 사용을 뜻하며, 백엔드의 `LLM_PROVIDER=demo`와는 별개입니다. 백엔드가 demo라면 서버에서 템플릿을 반환합니다. 작업 결과의 `mode`로 demo/ai/calculated/retrieved를 구분할 수 있습니다.

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/health` | 실행 상태·연결 모드. 외부 연결 성공을 보장하지 않음 |
| GET | `/api/assistants` | 네 비서 목록 |
| POST | `/api/assistants/{policy,marketing,customer,sales}` | 비서 즉시 실행 |
| POST | `/api/jobs/{policy,marketing,customer,sales}` | 작업 큐에 등록, 202 응답 |
| GET | `/api/jobs/{job_id}` | 본인 작업의 상태와 결과 |
| GET | `/api/auth/me` | 로그인 사용자 확인. 로컬 모드는 공용 local 사용자 |
| GET | `/api/history?limit=20` | Supabase의 본인 작업 내역. 저장 미설정이면 503 |

동기·비동기 실행에 공통으로 사용하는 요청:

```json
{
  "message": "신메뉴 딸기 라떼를 홍보하고 싶어요.",
  "format": "카드뉴스 문안",
  "profile": {
    "name": "오후의 커피",
    "owner": "김가게",
    "category": "카페·베이커리",
    "region": "서울 마포구"
  }
}
```

기존 프론트엔드와 호환되는 응답:

```json
{
  "text": "작업 결과 본문",
  "sources": [],
  "mode": "demo"
}
```

매출 요청은 `message`에 CSV 문자열을 넣습니다. 필수 열은 `매출`/`매출액`/`amount`/`sales`, 선택 열은 `날짜`/`date`/`일시`/`datetime`과 `시간`/`hour`입니다. 시간은 0~23 또는 HH:mm, UTF-8 BOM·CRLF·따옴표로 감싼 천 단위 쉼표를 지원합니다. 음수는 환불로 합산합니다. `Decimal`로 계산하며 금액은 원 단위 반올림합니다. 최대 1,000,000자, 50,000개 내역입니다. 기타 질문은 20,000자까지입니다. 잘못된 CSV는 422로 응답합니다.

## HyperCLOVA X 연결

`.env` 설정 예시:

```dotenv
LLM_PROVIDER=hyperclova
CLOVA_API_KEY=본인의_API_키
CLOVA_MODEL=HCX-005
```

서버를 다시 시작하면 정책·홍보·고객응대 요청을 CLOVA Studio v3 Chat Completions에 전달합니다. 모델 접근 권한은 본인의 계정에서 확인해야 합니다. 실제 API 사용 시 요금이 발생할 수 있습니다. 매출 숫자는 모델 대신 Python으로 계산합니다. 외부 요청 제한 시간은 25초이며 모델 오류를 체험 결과로 바꾸지 않습니다.

다른 OpenAI 호환 서버는 `LLM_PROVIDER=compatible`, `COMPATIBLE_API_KEY`, `COMPATIBLE_BASE_URL`(보통 `/v1`까지), `COMPATIBLE_MODEL`을 설정합니다.

## 정책 RAG 문서

`data/policies.json`은 기본적으로 빈 배열입니다. 확인된 원문을 다음 형식으로 등록하세요.

```json
[
  {
    "title": "공식 공고 제목",
    "url": "https://official.example/policy",
    "content": "직접 확인한 정책 원문과 게시일, 신청 기간 등"
  }
]
```

파일 저장 후 서버를 다시 시작하세요(`--reload`의 기본 대상은 Python 파일이므로 JSON 변경은 수동 재시작이 필요합니다). 초기 검색기는 한글 bigram과 단어 일치로 최대 3개 원문을 선택합니다. 임베딩·벡터 검색·Search-R1이 아닙니다. AI 모드에서 검색 결과를 모델에 전달하고 실제 검색한 문서 URL만 `sources`에 반환합니다. 문서가 없으면 정책을 만들어 내지 않고 검색 결과가 없다고 답합니다. 체험 모드에서는 등록 문서 발췌만 반환합니다. 공고의 최신성·신청 자격 검증은 별도로 구현해야 합니다.

## Supabase Auth / PostgreSQL 연결

1. 본인의 Supabase 프로젝트에서 `supabase/schema.sql`을 실행합니다.
2. `.env`에 프로젝트 URL과 publishable key를 설정합니다.
3. `AUTH_MODE=supabase`, `PERSIST_HISTORY=true`를 설정하고 서버를 다시 시작합니다.
4. 프론트엔드에서 Supabase 로그인 후 `Authorization: Bearer <access_token>`을 보내도록 연결합니다.

현재 프론트엔드에는 로그인 UI와 토큰 전달이 없으므로, 연결 전에는 `AUTH_MODE=local`로 사용하세요. Supabase 모드에서는 토큰 없는 요청을 401로 거절하며 로컬 사용자로 우회하지 않습니다. Auth의 `/auth/v1/user`에서 사용자를 확인하고, DB 요청에도 같은 사용자 토큰을 전달해 RLS를 적용합니다. service-role 키는 사용하지 않습니다. 작업 이력에는 가게 정보와 결과가 저장되며, CSV 원문은 저장하지 않습니다. 현재 데이터베이스는 실제 계정에 연결하거나 변경하지 않았습니다.

## 큐 / Worker

서버가 시작되면 개발용 Worker도 함께 시작됩니다. `/api/jobs/{assistant_id}`로 등록한 작업은 queued → running → completed/failed 상태로 진행됩니다. `/api/jobs/{job_id}`를 조회해 완료된 결과를 받으세요. 사용자별 소유권 검사와 최대 100개 작업 제한이 있습니다. 완료 작업은 한도를 넘으면 오래된 것부터 제거합니다.

이 큐는 **프로세스 메모리에만 보관**되므로 재시작·자동 reload 시 작업 내역이 사라집니다. 로컬 개발에서 Uvicorn 단일 프로세스로 사용하세요. 외부 브로커·별도 Worker 프로세스·재시도·영속 큐는 구현하지 않았습니다. 운영용 대용량 이미지·영상 작업에는 Redis 등의 외부 큐를 연결해야 합니다. `AUTH_MODE=local`은 한 명이 개발하는 localhost용 모드로 사용자 격리를 제공하지 않습니다.

## 테스트

```bash
source .venv/bin/activate
python -m unittest discover -s tests -v
```

API 응답, CSV 합계·환불·피크타임·잘못된 입력, CORS, 작업 큐·소유권·한도, RAG 출처, Supabase 인증 요청, CLOVA 요청 형식과 오류를 검증합니다. 외부 서비스는 mock을 사용하므로 실계정 연결 검증을 뜻하지 않습니다.

## 공식 문서

- [FastAPI 시작하기](https://fastapi.tiangolo.com/tutorial/first-steps/)
- [CLOVA Studio v3 Chat Completions](https://api.ncloud-docs.com/docs/clovastudio-chatcompletionsv3)
- [Supabase JWT 인증](https://supabase.com/docs/guides/auth/jwts)
- [Supabase Data REST API](https://supabase.com/docs/guides/api)

# AM-ERICA Backend · Spring Boot

React(Vite) → **Spring Boot 중앙 API** → PostgreSQL / Python AI 서버(gRPC) 구조의 백엔드입니다.

```
React + TypeScript + Vite
        │  HTTPS / REST · SSE
        ▼
Spring Boot (Java 21, Spring Boot 3.5)
  ├─ Spring Security   JWT 인증 · 매장 접근 권한        auth/
  ├─ Store Service     매장 · 사용자 관리               store/, user/
  ├─ Data Service      매출 · 리뷰 · 정책 데이터         data/
  └─ AI Orchestrator   비서 호출 · 결과 통합 · 호출 이력   ai/
        │ JPA                         │ gRPC (내부 키 인증)
        ▼                             ▼
   PostgreSQL               Python AI Server
                              ├─ PolicyAssistant      :50051  정책 (Search-R1)
                              ├─ PromotionAssistant   :50052  홍보 (PosterLlama)
                              ├─ CustomerAssistant    :50053  고객 응대 (SCRABLE)
                              └─ ManagementAssistant  :50054  경영 · 매출 예측 (Chronos)
```

## 로컬 실행

```bash
./gradlew bootRun
```

- JDK 21이 없으면 Gradle이 자동으로 내려받습니다. 다만 **Gradle 자체 실행에는 Java 17 이상**이 필요합니다. 이 Mac의 기본 `java`는 11이므로 다음처럼 실행합니다.

  ```bash
  JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew bootRun
  ```

- 서버: `http://localhost:8000` (React Vite 프록시 `/api → 127.0.0.1:8000`과 동일한 포트)
- 기본 `local` 프로필: H2 메모리 DB + **내장 mock gRPC 비서 서버**(127.0.0.1:50051~50054). Python·PostgreSQL 없이 전체 흐름을 확인할 수 있습니다. mock 응답에는 `[MOCK]`이 붙습니다.
- 데모 계정이 자동 생성됩니다: `demo@america.local` / `demo1234!` (매장 ID 1, 매출 42일치, 리뷰 2건)

## API 문서 보는 방법

서버 실행 후 브라우저에서:

| 문서 | 주소 |
|---|---|
| Swagger UI (직접 호출 가능) | http://localhost:8000/swagger-ui.html |
| OpenAPI JSON | http://localhost:8000/v3/api-docs |
| 상태 확인 (DB · 4개 비서 gRPC 상태) | http://localhost:8000/actuator/health |
| gRPC 계약 | `src/main/proto/america/ai/v1/*.proto` |

Swagger UI 사용 순서:

1. **1. 인증 → `POST /api/auth/login`** → *Try it out* → 데모 계정을 넣고 *Execute*
2. 응답의 `accessToken` 값을 복사
3. 오른쪽 위 **Authorize** 버튼 → 토큰만 붙여넣기(`Bearer ` 접두사 없이) → *Authorize*
4. **4. AI 비서 → `GET /api/stores/{storeId}/ai/briefing`** 등에서 `storeId=1`로 호출

OpenAPI JSON은 Postman·Insomnia에서 *Import → URL*로 불러올 수도 있고, 프런트엔드 타입 생성(`openapi-typescript` 등)에 사용할 수 있습니다. `prod` 프로필에서는 문서가 기본 비활성화되며 `API_DOCS_ENABLED=true`로 켤 수 있습니다.

SSE(`POST /api/stores/{id}/ai/policy/stream`)는 Swagger UI에서 스트림이 끝난 뒤 한 번에 표시됩니다. 실시간으로 보려면:

```bash
TOKEN=$(curl -s -X POST localhost:8000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"demo@america.local","password":"demo1234!"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -N -X POST localhost:8000/api/stores/1/ai/policy/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"question":"정책자금 알려줘"}'
```

## 주요 API

| 영역 | 엔드포인트 |
|---|---|
| 인증 | `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/naver`, `GET /api/users/me` |
| 매장 | `POST/GET /api/stores`, `GET/PUT /api/stores/{storeId}` |
| 데이터 | `PUT/GET /api/stores/{id}/sales`, `POST/GET /api/stores/{id}/reviews`, `PUT .../reviews/{reviewId}/reply`, `GET/POST /api/policies` |
| 정책 비서 | `POST /api/stores/{id}/ai/policy`, `POST .../ai/policy/stream` (SSE) |
| 홍보 비서 | `POST /api/stores/{id}/ai/promotion/poster` |
| 고객 응대 비서 | `POST /api/stores/{id}/ai/customer/reply` |
| 경영 비서 | `POST /api/stores/{id}/ai/management/forecast` |
| 통합 | `GET /api/stores/{id}/ai/briefing` (매출 예측 + 정책 병렬 호출), `GET .../ai/logs` |

- 다른 사용자의 매장은 존재 여부를 드러내지 않도록 404입니다.
- 오류는 RFC 9457 ProblemDetail 형식이며 `code` 필드(`STORE_NOT_FOUND`, `AI_UNAVAILABLE` 등)로 구분합니다.
- gRPC 오류 변환: `UNAVAILABLE→503`, `DEADLINE_EXCEEDED→504`, `INVALID_ARGUMENT→400`, `RESOURCE_EXHAUSTED→429`, 그 외 `502`. Python 쪽 오류 메시지는 응답에 노출하지 않고 로그에만 남깁니다.
- 통합 브리핑은 한 비서가 실패해도 나머지 결과를 반환합니다(`status: ok | failed`).
- AI 호출 이력(`ai_logs`)에는 비서·성공 여부·지연 시간만 저장하고 질문·리뷰 원문은 저장하지 않습니다.

## Python AI 서버 연동 (gRPC)

### 정책 비서 MVP 실서버 연결 (2026-11-16)

기존 gRPC/SSE 경로를 재사용합니다. `local,policy-mvp,supabase` 프로필은 정책만 실제 Python 서버로 연결하고 나머지 세 비서는 기존 로컬 mock으로 유지하며, 계정·매장·알림 설정은 Supabase에 저장합니다. 기본 `local` 실행의 mock 동작은 바뀌지 않습니다. 프론트에 내부 키나 검색 API 키를 넣지 않습니다.

Mac에서 GPU 서버에 SSH 터널을 열어 둡니다. 로컬 55051을 사용하므로 기존 mock 포트 50051과 충돌하지 않습니다.

```bash
ssh -N -L 55051:127.0.0.1:50051 root@223.130.161.182
```

서버에서 전달받은 클라이언트 설정을 `.env.policy-mvp`로 저장합니다. 이 파일에는 같은 `AI_INTERNAL_KEY`와 `AI_POLICY_HOST=127.0.0.1`, `AI_POLICY_PORT=55051`, `AI_POLICY_TLS=false`를 넣습니다. 기존 `.env`의 로그인·DB·SMTP 비밀값은 유지합니다. `.env.policy-mvp`는 policy-mvp 프로필에서 자동으로 읽으며 Git에서 제외됩니다. `.env`에 `SPRING_PROFILES_ACTIVE=local,policy-mvp,supabase`를 지정하면 `./gradlew bootRun`으로도 같은 설정을 적용합니다. 다른 터미널에서:

```bash
cd ~/Desktop/am-erica-backend
bash scripts/run-policy-mvp.sh
```

기존 백엔드가 8000에서 실행 중이면 사용자가 해당 개발 서버를 재시작해야 합니다. 스크립트는 기존 프로세스를 종료하지 않습니다. 프론트는 기존 `npm run dev`와 `/api` 프록시를 그대로 사용합니다. `topK`는 1~5이며 내부 호출도 이 범위로 제한합니다. 검색 서비스 한도 소진은 `AI_SEARCH_LIMIT` 오류로 전달하며 mock 정책으로 대체하지 않습니다. 기본 gRPC 제한시간은 90초, 프론트 요청 제한시간은 120초입니다.

GPU 서버에서 실제 프론트의 JWT 복원·매장 등록·질문 전송 함수 → Vite 프록시 → Spring → Python gRPC → SerpAPI → 공식 출처 수신을 검증했습니다. 로컬 테스트 회원가입으로 인증을 확인했으며 네이버 OAuth 실로그인은 별도 설정을 유지합니다. 외부에 새로운 서비스를 공개하거나 이메일을 보내는 검증은 수행하지 않았습니다. 현재 SerpAPI 검색 한도가 소진돼 새 질의 검색은 복구가 필요하며, 확인된 자금 공고 질의는 제공자 캐시 응답으로 최종 검증했습니다.

계약은 `src/main/proto/america/ai/v1/`의 proto 파일이 기준입니다. Python 쪽 코드 생성:

```bash
pip install grpcio grpcio-tools grpcio-health-checking
python -m grpc_tools.protoc -I src/main/proto --python_out=ai_server --grpc_python_out=ai_server \
  src/main/proto/america/ai/v1/*.proto
```

Python 서버가 지켜야 할 것:

1. 메타데이터 `x-internal-key`가 `AI_INTERNAL_KEY`와 같지 않으면 `UNAUTHENTICATED`로 거부합니다. `x-request-id`는 로그 추적용입니다.
2. 표준 gRPC Health 서비스(`grpc.health.v1.Health`)를 등록합니다. `/actuator/health`가 이것으로 비서 상태를 표시합니다.
3. 잘못된 입력은 `INVALID_ARGUMENT`, 과부하는 `RESOURCE_EXHAUSTED`, 모델 준비 전에는 `UNAVAILABLE`을 반환합니다.
4. `AskStream`은 `delta`를 여러 번 보낸 뒤 마지막 메시지에 `sources`와 `done=true`를 보냅니다.

Spring에서 mock 대신 실제 Python 서버에 연결:

```bash
AI_MOCK_ENABLED=false AI_INTERNAL_KEY='같은-키' \
AI_POLICY_HOST=127.0.0.1 AI_POLICY_PORT=50051 \
./gradlew bootRun
```

비서별 `AI_{POLICY|PROMOTION|CUSTOMER|MANAGEMENT}_{HOST|PORT}`로 주소를 바꿀 수 있습니다. 네 비서를 한 포트의 한 프로세스에서 제공해도 됩니다. 기본 마감 시간은 30초(`ai.grpc.deadline`)이며, 채널은 비서별로 하나씩 유지되고 끊기면 자동 재연결됩니다.

## 운영(prod) 프로필

```bash
psql -U america -d america -f database/schema.sql   # 최초 1회
# 운영 환경에서는 아래 필수 키를 .env 또는 프로세스 환경변수로 지정합니다.
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/am-erica-backend-0.1.0.jar
```

- 필수 환경변수: `JWT_SECRET`(32바이트 이상), `AI_INTERNAL_KEY`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. 없으면 시작 단계에서 실패합니다.
- prod는 테이블을 자동 생성·수정하지 않습니다(`ddl-auto: none`). 스키마 변경은 SQL로 직접 적용합니다.
- 외부 공개 시 TLS 리버스 프록시 뒤에 두고, Python 서버가 다른 호스트에 있으면 `AI_GRPC_TLS=true`를 사용하세요.

## 테스트

```bash
./gradlew test
```

- `ApiIntegrationTest`: 회원가입 → 매장 → 매출/리뷰 → 4개 비서 호출 → 통합 브리핑 → 이력, SSE 스트림, 매장 격리, 인증, 검증 오류, 헬스체크. 실제 gRPC 클라이언트가 127.0.0.1의 mock 서버를 호출합니다.
- `SchemaValidationTest`: `database/schema.sql`과 JPA 엔티티 일치 여부 (H2 PostgreSQL 호환 모드)
- `GrpcErrorsTest`: gRPC 상태 → HTTP 오류 변환

실제 PostgreSQL과 Python 모델 서버 연동은 테스트에 포함되지 않습니다.

## 아직 구현하지 않은 것

- Python AI 서버 자체 (Search-R1 · PosterLlama · SCRABLE · Chronos · LangGraph · HyperCLOVA X). Spring은 proto 계약과 클라이언트만 제공합니다.
- pgvector 임베딩 검색: 정책 RAG는 Python 서버가 담당한다는 전제이며 스키마에 포함하지 않았습니다.
- 매장 멤버(직원) 권한과 관리자 역할: 현재는 매장 소유자만 접근하며, `POST /api/policies`는 로그인한 모든 사용자가 호출할 수 있습니다.


## 네이버 로그인

`.env.example`에는 로컬 네이버 로그인에 필요한 세 항목만 있습니다. 이 파일을 `.env`로 복사하고 Client ID와 Client Secret을 입력하세요. 백엔드는 실행 디렉터리의 `.env`를 Spring 설정으로 자동으로 읽습니다. IDE 실행 시 working directory를 `am-erica-backend`로 지정하세요. `export`나 따옴표 없이 `KEY=value` 형식으로 작성합니다. `.env`는 Git에서 제외되어 있으며 운영 환경변수로도 같은 키를 지정할 수 있습니다.

```dotenv
NAVER_CLIENT_ID=발급받은_Client_ID
NAVER_CLIENT_SECRET=발급받은_Client_Secret
NAVER_REDIRECT_URI=http://localhost:7001/api/auth/callback/naver
```

프론트엔드 `.env`의 `VITE_NAVER_CLIENT_ID` 및 `VITE_NAVER_REDIRECT_URI`와 같은 애플리케이션/콜백을 설정하고 양쪽 서버를 재시작하세요. **Client Secret은 백엔드에만 보관합니다.** 키가 없으면 일반 API는 실행되지만 네이버 로그인은 `503 NAVER_NOT_CONFIGURED`를 반환합니다.

네이버 개발자 센터의 서비스 URL은 `http://localhost:7001`, Callback URL은 `http://localhost:7001/api/auth/callback/naver`로 등록합니다. 이 GET 콜백은 Vite가 프론트엔드로 전달합니다. 프론트엔드는 저장해 둔 일회성 `state`와 10분 유효시간을 확인한 후 아래 API를 호출합니다. 이 콜백 URL을 백엔드 포트 8000으로 직접 열면 안 됩니다. 운영 프록시에서도 GET 콜백 경로는 프론트엔드로 전달해야 합니다.

```http
POST /api/auth/naver
Content-Type: application/json

{"code":"네이버 인증 코드","state":"프론트엔드에서 검증한 state","redirectUri":"http://localhost:7001/api/auth/callback/naver"}
```

1. 서버는 콜백 주소가 설정과 정확히 같은지 검사합니다.
2. 고정된 네이버 토큰 API에 Client Secret을 POST 본문으로 전달하고, 받은 네이버 토큰으로 프로필 API를 조회합니다. 연결/응답 제한시간이 있으며 토큰과 네이버 오류 원문은 응답에 노출하지 않습니다.
3. 네이버 프로필 `id`에 대응하는 계정을 조회하거나 생성합니다. 같은 이메일의 기존 계정에 자동 연결하지 않고 `409 NAVER_EMAIL_CONFLICT`를 반환합니다. 이메일 미동의는 `NULL`, 이름 미제공은 `사장님`으로 처리합니다. 네이버 계정은 비밀번호 로그인이 불가능합니다.
4. 기존 형식의 **서비스 JWT**를 반환합니다. 네이버 access/refresh token은 저장하거나 프론트엔드에 반환하지 않습니다.

```json
{"accessToken":"서비스 JWT","tokenType":"Bearer","expiresIn":43200}
```

프론트엔드는 이 토큰으로 `GET /api/users/me`를 검증하고 보호 API를 이용합니다. 이메일 미제공 사용자는 응답에서 `email: ""`로 표시됩니다. 네이버 계정 연결 해제 및 서비스 토큰 갱신은 이번 구현에 포함되지 않습니다.

### DB 반영

- 로컬 H2: 재시작 시 새 매핑으로 생성됩니다. 메모리 DB이므로 사용자와 가게 데이터는 재시작하면 초기화됩니다.
- 새 PostgreSQL: `database/schema.sql`을 실행합니다.
- 기존 PostgreSQL: 배포 전에 `database/migrations/001_naver_login.sql`을 **1회** 실행합니다. 기존 사용자 데이터는 유지하면서 `naver_id` 고유 컬럼을 추가하고 소셜 계정에 이메일/비밀번호가 없어도 저장할 수 있게 합니다. 자동 실행하지 않습니다.

### 검증

`NaverClientTest`는 네이버 HTTP 응답을 모의하여 코드 교환·프로필 조회·타임아웃/오류 처리를 검증합니다. `NaverAuthIntegrationTest`는 계정 생성·재로그인·이메일 충돌·JWT 및 보호 API 접근을 실제 H2/보안 필터를 통해 검증합니다. 실제 네이버 계정 인증은 개발자 센터 설정과 사용자 동의가 필요합니다.

참고: [네이버 로그인 API](https://developers.naver.com/docs/login/api/api.md), [프로필 API](https://developers.naver.com/docs/login/profile/profile.md).


## 정책 비서 채팅

정책 채팅은 `chat_conversations`와 `chat_messages` 테이블에 계정별로 저장합니다. JWT의 사용자 ID로 대화·매장 소유권을 검사하며, 클라이언트가 보낸 사용자 ID나 대화 문맥을 신뢰하지 않습니다. Supabase DB 연결은 기존 설정을 사용합니다.

- `GET /api/me/policy/conversations`: 마지막 활동 순 최근 대화방 5개.
- `POST /api/me/policy/conversations` `{ "storeId": 1 }`: 첫 질문 또는 수동 메일 검색 때 대화방 생성. 프론트의 ‘새 대화’ 클릭만으로는 빈 방을 생성하지 않습니다.
- `GET /api/me/policy/conversations/{id}`: 해당 방의 모든 질문·답변·출처·처리 상태 복원.
- `POST /api/me/policy/conversations/{id}/messages` `{ "clientMessageId": "UUID", "question": "질문" }`: 질문과 처리 중 메시지 저장 → gRPC → 답변 저장 → SSE 완료(`done`). `delta`, `sources`, `error` 이벤트는 기존 방식입니다.
- `POST /api/me/policy/conversations/{id}/messages/{clientMessageId}/stop`: 중지 및 부분 답변 보관.
- `POST /api/me/policy/conversations/{id}/email` `{ "clientMessageId": "UUID" }`: 기존 수동 정책 검색·SMTP 발송 로직을 실행하고 같은 대화방에 결과 보관. 완료된 동일 요청을 다시 보내도 메일을 중복 발송하지 않습니다. 오전 6시 자동 발송 설정은 변경하지 않습니다.

계정 행 잠금으로 여러 기기의 동시 요청을 직렬화하며 여섯 번째 방을 생성하면 마지막 활동이 가장 오래된 방과 그 메시지를 같은 트랜잭션에서 삭제합니다. 보관 중인 각 방의 메시지 수를 40개로 자르지 않습니다. 모델 문맥은 DB에서 정상 완료된 최근 네 번의 질의응답만 추려 각 메시지 최대 2,000자와 최근 출처 URL을 전달합니다. 이전 `/api/stores/{id}/ai/policy[/stream]`는 호환용으로 유지하며 해당 경로는 대화를 저장하지 않습니다.

SSE 완료 전에 DB 저장 실패를 확인하면 `CHAT_SAVE_FAILED` 오류를 보냅니다. 중지/실패한 답변은 문맥에서 제외하고, 서버 중단으로 남은 처리 중 메시지는 모델 제한시간 + 60초 이후 조회 시 `CHAT_INTERRUPTED`로 복구합니다. 브라우저에만 남아 있던 과거 대화는 자동 이전하지 않습니다.

신규 Supabase 프로젝트는 `database/supabase-bootstrap.sql`, 기존 프로젝트에서 대화 테이블이 없으면 `database/migrations/004_policy_conversations.sql`을 사용하세요. 이미 안내받은 두 테이블을 만들었다면 SQL이나 환경변수를 추가할 필요 없이 백엔드를 재시작하면 됩니다. 앱 테이블은 Spring의 DB 연결로 접근하고 Supabase 공개 Data API로 읽도록 허용하지 않습니다.

검증: `PolicyConversationIntegrationTest`는 전체 메시지 복원, 5개 제한과 자식 메시지 삭제, 동시 생성, 타 계정 접근 거부, 중복 요청, 중지/장애 복구, 저장 실패 시 SSE 완료 차단, 수동 메일 결과 저장을 격리된 H2와 모의 AI/메일로 검사합니다. 실제 메일은 발송하지 않습니다.

현재 local 프로필 기본값은 **내장 mock 응답**입니다. 실제 Search-R1을 사용하려면 해당 모델을 제공하는 `PolicyAssistant.AskStream` gRPC 서버를 별도로 실행하고 `AI_MOCK_ENABLED=false`, `AI_POLICY_HOST`, `AI_POLICY_PORT`, `AI_INTERNAL_KEY`를 서버 환경에 지정하세요. 인증은 기존 `x-internal-key` 메타데이터를 사용합니다. 이 저장소는 모델 추론 서버 자체를 포함하지 않습니다. 현재 기본 gRPC 제한시간은 30초(`ai.grpc.deadline`)이며, 프론트엔드 요청 제한시간은 120초입니다.


## 오전 6시 정책 이메일 리마인드

정책 비서 화면의 `오전 6시 이메일 알림` 스위치로 계정별 수신 여부를 저장합니다. 기본은 OFF이며,
수신 주소는 인증된 계정의 이메일만 사용합니다. ON을 저장할 때 본인 소유 가게를 연결합니다.
`GET /api/me/policy-reminder`, `PUT /api/me/policy-reminder` (`{"enabled":true,"storeId":1}` / `{"enabled":false}`)는 JWT가 필요합니다.
설정 조회만으로 가게를 만들거나 메일을 보내지는 않습니다.

발송 규칙:
- 매일 **06:00 Asia/Seoul**에 시작합니다. 여러 수신자는 순차 발송하므로 도착 시간은 조금 다를 수 있습니다.
- 정책 AI로 오늘 신청 가능한 정책을 검색하고, DB `policies`에 저장된 전국 또는 가게 지역 정책도 조회합니다. 넓은 지역도 포함합니다(예: 서울 → 서울 강남구). 지역명은 동일 표기를 사용하세요.
- `apply_start`가 미래이면 제외하고, `apply_end`가 오늘 이후(오늘 포함)인 정책을 모두 보냅니다. 새 정책만 보내거나 이전에 보낸 정책을 제외하지 않습니다.
- 마감일이 없는 정책은 기간을 확인할 수 없어 제외합니다. 시작일이 없고 마감일만 있는 기존 데이터는 접수 중으로 취급합니다.
- 검색이 정상 완료되면 진행 중 정책이 0건이어도 확인 메일을 매일 보냅니다. AI 검색 실패 또는 빈 응답은 발송하지 않습니다. 정책 분류는 금융·판로 등이므로 업종 자격을 자동 판정하지 않으며 공식 공고 확인을 안내합니다.
- OFF이면 보내지 않습니다. 같은 계정·날짜의 성공한 발송은 다시 실행하지 않습니다. DB 계정 행 잠금으로 동시 실행을 직렬화합니다.
- AI 검색 또는 SMTP 오류는 해당 계정만 실패 처리하고 나머지를 계속 발송합니다. 실패한 건은 발송 완료로 기록하지 않습니다. 자동 재시도/중단 중 놓친 시간의 소급 발송은 없으며 다음 날 정기 실행을 사용합니다.

**정책 데이터 수집과 모델 연동:** 정책 AI 검색 결과와 DB의 정책 원문·요약·접수 기간을 함께 사용합니다. 외부 공고 자동 수집기는 아직 없으며 Search-R1의 자유형 대화 응답을 공고로 저장하지 않습니다.
매일 빠짐없이 재안내할 정책은 검증한 데이터를 기존 `POST /api/policies`로 적재하거나 별도의 공고 수집기를 연결해야 합니다. 자유형 AI 검색 결과는 매번 달라질 수 있습니다.
이 API는 기존 인증 정책을 따르므로 운영 시 정책 등록 권한도 별도로 제한해야 합니다. `applyStart`(선택)와 `applyEnd`를 ISO 날짜로 전달합니다.

### Gmail SMTP 설정 (Gmail 발신 → 네이버 등 로그인 이메일로 수신)

1. 발신용 Google 계정에서 **2단계 인증**을 켜고 **앱 비밀번호**를 발급합니다. `MANAGER_PASSWORD`에는 일반 로그인 비밀번호가 아닌 앱 비밀번호를 표시용 공백 없이 입력합니다.
2. `MANAGER_EMAIL`에는 보내는 Google 계정의 전체 이메일 주소를 입력합니다. 네이버 수신 주소는 로그인 계정에서 가져오므로 별도의 수신 주소 환경변수나 네이버 SMTP 설정은 필요하지 않습니다.
3. 기존 `.env`의 SMTP 설정 부분을 아래 항목으로 교체하세요. 실제 값은 **백엔드 `.env`**에만 입력하고 프론트 `VITE_` 변수에는 넣지 마세요.

```dotenv
POLICY_REMINDER_ENABLED=true
SMTP_SERVER=smtp.gmail.com
SMTP_PORT=587
MANAGER_EMAIL=발신계정@gmail.com
MANAGER_PASSWORD=Google앱비밀번호
SMTP_SSL_ENABLED=false
SMTP_STARTTLS_ENABLED=true
FRONTEND_URL=http://localhost:7001
```

필수 SMTP 입력은 `SMTP_SERVER`, `SMTP_PORT`, `MANAGER_EMAIL`, `MANAGER_PASSWORD` 네 가지입니다.
백엔드는 `MANAGER_EMAIL`을 SMTP 인증 계정과 발신 주소에 함께 사용합니다.
기존 `SMTP_HOST`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` 변수는 더 이상 사용하지 않습니다.
기본 보안 설정은 587 / STARTTLS이며, SSL/STARTTLS 변수를 생략해도 이 기본값이 적용됩니다.
기존 `.env`에 `SMTP_SSL_ENABLED=true`, `SMTP_STARTTLS_ENABLED=false`가 있다면 위 예시처럼 바꾸세요.
465 포트를 사용한다면 `SMTP_SSL_ENABLED=true`, `SMTP_STARTTLS_ENABLED=false`로 설정합니다.

Google SMTP 설정: https://developers.google.com/workspace/gmail/imap/imap-smtp
Google 앱 비밀번호: https://support.google.com/accounts/answer/185833?hl=ko

4. 백엔드를 재시작(`./gradlew bootRun`)하고 정책 비서 화면에서 알림을 ON으로 설정합니다. SMTP가 비어 있거나 전역 발송이 false이면 설정은 저장되지만 준비 중 안내가 표시됩니다. 준비 상태는 설정 존재 여부이며 실제 인증 성공을 보증하지 않습니다.
5. 운영에는 기존 PostgreSQL DB에 `database/migrations/002_policy_reminders.sql`을 1회 적용합니다. 신규 DB는 갱신된 `database/schema.sql`을 사용합니다. 로컬 기본 H2 메모리 DB는 재시작 시 계정·정책·알림 설정이 사라지므로 지속 운영에는 prod/PostgreSQL을 사용하세요.
6. 배포 시 FRONTEND_URL을 실제 프론트 주소로 바꾸고 오전 6시에 서버가 실행 중이도록 유지합니다. 설정 저장 시 즉시 메일을 보내지는 않습니다.

자동 테스트는 모의 메일 발송기로 검증하며 실제 이메일을 전송하지 않습니다. SMTP 전달 직후 DB 커밋 전에 프로세스가 종료되는 극단적인 상황의 정확히 한 번 전달은 보장하지 않습니다.

## 로컬 프론트에서 GPU 정책 비서 사용

프론트의 정책 채팅은 `/api/stores/{id}/ai/policy/stream`으로 요청하고, 백엔드는 인증·매장 소유권 확인 후 gRPC를 호출합니다. 답변과 출처는 SSE로 전달됩니다.

1. GPU에서 `serve_policy.py --search-engine naver --search-timeout 30 --max-new-tokens 512`를 기존 체크포인트와 함께 실행합니다.
2. Cursor 원격 창의 Ports에서 GPU 50051 포트를 전달합니다. 현재 Mac 전달 주소는 `[::1]:50051`입니다. Cursor 연결을 유지하세요.
3. 백엔드 `.env`에 `AI_INTERNAL_KEY`를 GPU와 동일하게 설정하고 다음 값을 넣습니다.

```properties
AI_POLICY_MOCK_ENABLED=false
AI_POLICY_HOST=::1
AI_POLICY_PORT=50051
AI_GRPC_DEADLINE=90s
```

`AI_MOCK_ENABLED`는 local 기본값(true)을 유지합니다. `AI_POLICY_MOCK_ENABLED=false`는 정책 예시 서버만 끄며, GPU 연결 실패 시 예시 답변으로 대체하지 않습니다. 다른 세 비서는 기존 예시 서버를 유지합니다. 정책 답변의 최대 대기 시간은 GPU 85초, 백엔드 90초, 프론트 120초입니다.

별도 SSH 터널을 쓴다면 `ssh -N -L 127.0.0.1:50061:127.0.0.1:50051 root@223.130.161.182` 실행 후 host를 `127.0.0.1`, port를 `50061`로 바꿉니다. 외부에 GPU 포트를 공개할 필요가 없습니다.

4. 백엔드를 재시작하고 프론트 `http://localhost:7001`에 로그인한 뒤 정책 비서에서 질문합니다. local 프로필은 H2 메모리 DB이므로 재시작하면 서버의 개발용 계정·가게·이력이 초기화됩니다. 로그인 후 채팅하면 브라우저의 가게 정보로 매장을 다시 등록합니다.
5. 답변에 `[MOCK]`가 없고 실제 공식 출처 링크가 표시되는지 확인하세요. 다른 질문의 정확성은 별도로 검증해야 합니다.

SerpAPI 키는 GPU에만 필요합니다. 내부 키나 SerpAPI 키를 프론트 `VITE_*` 변수에 넣지 마세요.


## Supabase에 매장 정보와 비서 선택 계속 저장하기

이미지는 기존 `frontend-image/staff` Storage 경로에 그대로 두고, DB에는 로그인 계정의 매장과 비서 성별 선택값만 저장합니다.
네이버 로그인/서비스 JWT를 계속 사용하며 Supabase Auth나 프론트 Supabase SDK 설치는 필요하지 않습니다.

1. 기존 `am-erica-images` 프로젝트 → **Connect → Direct (Connection string)** → **Session pooler**를 선택합니다.
   `5432`는 포트입니다. Host는 `aws-…pooler.supabase.com` 형태의 별도 주소이며 대시보드의 값을 그대로 복사합니다(지역명으로 추측하지 않습니다).
2. 앱 테이블이 없는 신규 DB라면 **SQL Editor**에서 `database/supabase-bootstrap.sql` 내용을 1회 실행합니다.
   이미 이 앱의 테이블이 있다면 기존 적용 이력을 확인하고 누락된 `database/migrations/001…004`만 순서대로 적용한 뒤 `database/supabase-security.sql`을 실행합니다.
   bootstrap은 기존 테이블을 지우거나 덮어쓰지 않습니다. 이미 테이블이 있으면 오류로 전체 트랜잭션을 취소합니다.
   Supabase의 `auth`/`storage` 스키마 및 이미지 버킷은 수정하지 않습니다.
3. 백엔드 `.env`의 기존 로그인·SMTP·GPU 설정을 유지하면서 다음 항목을 추가합니다.

```dotenv
SPRING_PROFILES_ACTIVE=local,supabase
DB_URL=jdbc:postgresql://접속창의Host:5432/postgres?sslmode=require
DB_USERNAME=postgres.iyfmiswfprjohqoffugb
DB_PASSWORD=프로젝트DB비밀번호
JWT_SECRET=직접생성한고정된랜덤키
```

`DB_USERNAME`은 이 프로젝트 Session pooler 기준 예시이며 최종 값은 Connect 창에서 확인합니다.
`DB_PASSWORD`는 프로젝트 생성 시 지정한 DB 비밀번호이며 Supabase API key가 아닙니다. 특수문자를 URL에 넣을 필요 없이 별도 값으로 입력하세요.
`.env`는 Spring Properties 문법으로 읽습니다. 따옴표/export를 붙이지 말고, 비밀번호에 역슬래시가 있다면 `\\`로 이스케이프합니다.
`JWT_SECRET`은 `openssl rand -hex 32` 출력값을 직접 `.env`에 넣고 유지하세요. 채팅이나 프론트 환경변수로 공유하지 않습니다.
기존 `.env`에 `SPRING_PROFILES_ACTIVE=local`이 있다면 중복 추가하지 말고 `local,supabase`로 교체합니다.
이 조합은 기존 로컬 AI/GPU 연결 설정을 유지하면서 DB만 PostgreSQL로 전환하고 데모 데이터 자동 삽입을 끕니다.
Supabase 프로필은 `ddl-auto=validate`로 테이블을 검증하며 자동 생성/삭제하지 않습니다. 테이블을 먼저 준비해야 합니다.
운영 배포에서는 `prod,supabase`와 실제 AI 서버 설정을 사용합니다.
4. `./gradlew bootRun`으로 재시작한 뒤 다시 로그인합니다. 새 DB에는 기존 H2 계정이 자동 복사되지 않습니다.
   저장했던 매장이 있으면 불러오고, 없으면 초기 설정 화면에서 한 번 저장합니다.

API와 동작:
- `GET /api/me/settings`: 현재 계정 매장(없으면 null), 비서 선택 조회. 조회 자체로 매장을 만들지 않습니다.
- `PUT /api/me/store-profile`: `{name,owner,category,region}` 저장. 현재 계정 매장을 갱신하며 반복 저장/이름 변경으로 새 매장을 만들지 않습니다.
- `PATCH /api/me/staff-preferences/{policy|marketing|customer|sales}`: `{gender:"boy"|"girl"}` 저장.
- 저장 성공 응답 후에만 화면에 반영합니다. 조회가 실패하면 새 계정으로 간주하지 않고 재시도 화면을 표시합니다.
- 새 계정의 기본 비서는 정책/경영 남성, 홍보/고객응대 여성입니다. 선택은 계정별로 유지됩니다.
- 기존 정책 채팅에서 생성한 매장이 여러 개라면 가장 최근 본인 소유 매장을 사용하고, 이후 설정 저장 시 해당 매장을 고정합니다.
- 기존 브라우저 localStorage의 매장/성별 값은 계정 DB의 값을 덮어쓰지 않습니다. DB에 없는 설정은 한 번 다시 저장하세요.
- 현재 이용 내역과 정책 채팅 기록은 여전히 브라우저 저장입니다. 이번 변경 범위는 매장 설정과 비서 선택입니다.

SQL의 RLS는 공개 Data API에서 앱 테이블 접근을 기본 차단합니다. Spring은 Connect에서 받은 서버용 DB 계정으로 접근하며 JWT 사용자 ID에 따라 소유권을 검증합니다.
Supabase 공개 Storage 이미지 접근과는 별개입니다. Data API를 다른 용도로 쓰지 않는 경우 대시보드에서 비활성화할 수도 있습니다.

참고: https://supabase.com/docs/guides/database/connecting-to-postgres
참고: https://supabase.com/docs/guides/api/securing-your-api


### 지금 정책 검색 후 이메일로 받기

정책 비서 채팅창의 버튼은 `POST /api/me/policy-reminder/send-now`에 `{ "storeId": 1 }`을 보냅니다.
JWT 계정의 매장 소유권을 확인하고, 해당 계정의 이메일로만 발송합니다. 요청에서 수신 주소를 받지 않습니다.
응답 `{ "email": "...", "content": "..." }`의 content는 실제 발송 본문과 같으며 채팅창에도 표시합니다.

수동 발송과 오전 6시 자동 알림은 같은 검색·본문 구성 함수를 사용합니다. 정책 AI gRPC에 한국 기준 오늘 날짜로
현재 접수 가능한 정책 검색을 요청하고, DB의 접수 기간 내 지역/전국 정책도 개수 제한 없이 함께 안내합니다.
AI 검색은 학습 모델의 규격에 맞춘 topK=5이며 모든 외부 정책을 수집하거나 검색 결과를 policies 테이블에 자동 저장하는 기능은 아닙니다.
검색 실패/빈 응답/예시 응답(`[MOCK]`)/유효한 출처 URL이 없는 응답은 발송하지 않습니다. 모델 답변의 모든 사실과 신청 자격을 프로그램이 검증하는 것은 아닙니다. SMTP 결과가 불명확하면 자동 재시도하지 않습니다.

수동 발송에는 SMTP 설정만 필요하며 `POLICY_REMINDER_ENABLED=false` 또는 사용자 알림 OFF여도 가능합니다.
수동 발송은 알림 구독이나 자동 발송의 last_sent_date를 바꾸지 않습니다.
자동 발송은 `POLICY_REMINDER_ENABLED=true`, 사용자 알림 ON, 실행 중인 백엔드와 정책 AI 서버가 모두 필요합니다.
GET 설정 응답의 `manualDeliveryConfigured`는 SMTP 준비 여부, `deliveryConfigured`는 자동 발송 준비 여부입니다.
추가 테이블/컬럼은 필요하지 않습니다.


### 학습된 모델로 매일 갱신하기

현재 개발 연결은 `local,policy-mvp,supabase`입니다. `.env`의 `POLICY_REMINDER_ENABLED=true`와
사용자 화면의 오전 6시 알림 ON이 모두 필요합니다. `.env.policy-mvp`의 서버 주소/내부 키가 실제 모델 연결에 사용됩니다.
매일 06:00 Asia/Seoul에 수신 계정별로 새로운 날짜·매장 업종·지역을 전달해 `PolicyAssistant/Ask`를 호출합니다.
전날의 자유형 답변을 재발송하거나 그것을 정책 DB의 검증된 공고로 자동 등록하지 않습니다.
DB에 이미 등록된 기간 내 정책은 새 검색 결과와 함께 매일 다시 안내합니다.
한 계정의 검색 오류가 다른 수신자의 작업을 중단하지 않으며, 모델 오류 코드는 서버 로그에 기록됩니다.
정책 AI는 동시 추론 한 건만 지원하므로 스케줄러는 수신자를 순차 처리합니다. GPU가 다른 채팅을 처리 중이면
`AI_BUSY`로 실패할 수 있으며 자동 재시도는 없습니다. 같은 날 성공한 자동 발송은 last_sent_date로 중복을 방지합니다.
수동 버튼은 매번 모델을 호출하며 자동 알림 설정과 발송 날짜를 변경하지 않습니다.

백엔드 재시작 후 적용됩니다. 오전 6시에 백엔드·GPU 추론 서버·SSH 터널이 모두 실행 중이어야 합니다.
Mac이 잠자기 상태이거나 프로세스가 꺼져 있으면 실행되지 않으며, 놓친 시간의 소급 발송은 없습니다.
추가 테이블이나 SQL은 필요하지 않습니다.


## 홍보 엔진 자동 실행 (로컬)

백엔드 `.env`에 `AI_PROMOTION_AUTOSTART=true`, `AI_PROMOTION_PORT=50152`, `AI_PROMOTION_DIR=../Promotion-AI`를 추가하면 평소처럼 Spring Boot를 실행하는 것만으로 Python 홍보 엔진이 함께 시작됩니다. [설정 예시](.env.promotion.example)를 참고하세요. Python 3.12 가상환경은 최초 한 번 인접한 Promotion-AI 폴더에서 `python3.12 run.py --setup-only`로 준비합니다. 다른 Python 경로는 `AI_PROMOTION_PYTHON`으로 지정합니다.

CLOVA 키는 백엔드 `.env`의 `CLOVA_STUDIO_API_KEY`를 우선 사용하고, 없으면 Promotion-AI의 `.env`를 사용합니다. 기존 DB/JWT/네이버/정책 설정 및 내부 키는 덮어쓰지 마세요. Spring이 사용 중인 내부 키를 Python에 환경 변수로 전달하므로 두 파일의 내부 키를 수동으로 맞출 필요가 없습니다.

Spring은 엔진 준비를 기다리고, 정상 종료 시 자신이 시작한 Python을 함께 종료합니다. 준비 로그는 `build/promotion-ai.log`에 있습니다. 자동 실행 중에는 홍보 mock만 비활성화됩니다. 별도로 실행한 Python이 같은 포트를 쓰고 있다면 먼저 종료하세요. 원격 AI 서버를 사용하려면 `AI_PROMOTION_AUTOSTART=false`로 설정합니다.

프론트는 기존대로 `npm run dev`로 실행하고 http://localhost:7001/#marketing 에서 사용합니다. Codex/ChatGPT는 켜 둘 필요가 없습니다.

## 로컬 Scrable 고객응대 연결

고객응대 화면은 `POST /api/stores/{storeId}/ai/customer/reply`를 호출합니다.
로그인·가게 소유권 확인 후 `CustomerAssistant.DraftReply`로 리뷰와 가게 정보를 보내고,
응답 `reply`를 화면에 표시합니다. 프론트의 전체 데모 모드와 무관하게 실제 API를 사용합니다.

로컬 `.env`에 다음을 설정하고 Scrable과 백엔드를 각각 실행합니다.

```properties
AI_CUSTOMER_HOST=127.0.0.1
AI_CUSTOMER_PORT=50055
AI_CUSTOMER_MOCK_ENABLED=false
AI_CUSTOMER_DEADLINE=300s
AI_CUSTOMER_KEY_FILE=../Scrable/outputs/serve/internal-key
```

```bash
# 별도 터미널: 모델 서버 (최초 준비가 끝날 때까지 대기)
cd ../Scrable
bash scripts/run_local.sh start
```

기존 백엔드 실행 명령과 프로필은 그대로 사용합니다. `AI_CUSTOMER_KEY_FILE`이 설정되면
기존 파일을 읽어 고객응대 채널의 내부 키로만 사용합니다. 파일이 없거나 비어 있으면
시작 시 오류를 내므로 Scrable의 로컬 실행 준비를 먼저 완료해야 합니다.
파일 설정이 없으면 `AI_CUSTOMER_INTERNAL_KEY`, 그것도 없으면 기존 `AI_INTERNAL_KEY`를 사용합니다.
다른 비서의 키·대기 시간·mock 여부는 변경하지 않습니다.

프론트는 `http://localhost:7001/#customer`에서 이용하며 고객응대만 330초까지 기다립니다.
현재 Mac CPU에서 한 번의 생성은 보통 1~2분 걸립니다. 로그인 성공은 모델 서버 실행과
별개이므로 Scrable `start` 프로세스를 켜 두어야 합니다. 가게별 문서가 없으면 백엔드가
전달하는 실제 가게 이름·업종·지역·주소를 사용하며 예시 매장에 자동 매핑하지 않습니다.

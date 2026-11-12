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
| 인증 | `POST /api/auth/signup`, `POST /api/auth/login`, `GET /api/users/me` |
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
cp .env.example .env                                # 값 수정 후 환경변수로 주입
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

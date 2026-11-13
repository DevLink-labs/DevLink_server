# 서버 설정과 배포

[English](server-guide.md) · **한국어**

[프로젝트 소개](../README.ko.md) · [빠른 시작](../README.ko.md#빠른-시작) · [기여 가이드](../CONTRIBUTING.ko.md)

먼저 README의 빠른 시작으로 서버를 실행하세요. 이 문서는 화면·브로커 연결, 주요 API 경로, 실행 설정, 로컬 JVM 개발과 자체 배포를 안내합니다. 명령은 저장소 루트에서 실행합니다.

## 클라이언트와 화면 연결

1. [프론트엔드 문서](https://github.com/SSAFY-URAYAHA/DevLink_frontend)에 따라 화면을 실행하고 API 주소를 자신의 서버로 지정합니다.
2. 브라우저가 API를 직접 호출한다면 `.env`의 `CORS_ALLOWED_ORIGINS`에 실제 프론트엔드 Origin을 지정합니다. 기본값에는 로컬 프론트엔드 주소가 포함되어 있지 않습니다. 프론트엔드의 Vite 개발 프록시를 사용한다면 해당 README에 따라 같은 Origin을 통해 요청합니다.
3. 브로커의 Origin은 `WS_ALLOWED_ORIGINS`로 별도 설정하고 `docker compose up -d`로 설정을 반영합니다.
4. 사용자 가입·로그인, 팀 생성·참여, Agent 등록과 연결 토큰 발급을 진행합니다.
5. [클라이언트 문서](https://github.com/SSAFY-URAYAHA/DevLink_client)에 따라 연결할 중앙 서버 주소를 지정하고 브로커·MCP 서버를 AI 도구에 연결합니다.

로컬 API 실습은 [서버 API 실습](../docs/server-api-quickstart.md), 실제 Agent 협업은 클라이언트 문서를 참고하세요.

## 실행 구성 요소

| 구성 요소 | 역할 |
| --- | --- |
| REST API | 사용자·팀·Agent·작업·Git 데이터·추천·Context 요청 처리 |
| 브로커 WebSocket | Agent 접속 상태·Heartbeat와 작업·질문·답변 알림 전달 |
| 웹 SSE | 로그인한 사용자의 화면에 변경 알림 전달 |
| Outbox 처리기 | 업무 데이터와 전달 대기를 함께 저장하고 비동기 발행·재시도 수행 |
| PostgreSQL | 업무 데이터·이벤트·Outbox·Git·Context·revision 저장 |
| Context 갱신기 | 작업 이벤트를 바탕으로 파트 간 요약을 갱신하고 버전 관리 |

제공된 운영 배포 구성은 단일 앱 인스턴스 기준입니다. 여러 인스턴스로 확장하려면 세션·브로커 연결·웹 이벤트 전달을 공유하는 구성을 별도로 검토해야 합니다.

## 주요 API

사용자 API는 **세션 쿠키 + CSRF 토큰**, Agent 전용 REST API와 WebSocket은 **Bearer 토큰**으로 인증합니다. 사용자 변경 요청은 `/api/v1/auth/csrf`에서 발급받은 토큰을 전송하며, 로그인 후 토큰을 다시 조회합니다.

| 목적 | 주요 경로 |
| --- | --- |
| 인증 | `/api/v1/auth/signup`, `/api/v1/auth/login`, `/api/v1/auth/logout`, `/api/v1/auth/csrf` |
| 팀·Agent | `/api/v1/teams`, `/api/v1/agents/teams/{teamId}` |
| 작업 요청 | `POST /api/v1/tasks/{teamId}` |
| 상태·완료 | `PATCH /api/v1/tasks/{taskId}/status`, `POST /api/v1/tasks/{taskId}/complete` |
| 질문·답변 | `POST /api/v1/tasks/{taskId}/questions`, `POST /api/v1/tasks/{taskId}/answers` |
| Agent 작업 조회 | `/api/v1/teams/{teamId}/tasks`, `/api/v1/tasks/{taskId}`, `/api/v1/tasks/{taskId}/events` |
| 웹 작업 조회 | `/api/v1/web/teams/{teamId}/tasks`, `/api/v1/web/teams/{teamId}/tasks/{taskId}`, `/api/v1/web/teams/{teamId}/tasks/{taskId}/events` |
| 메시지 복구 | `GET /api/v1/agents/me/task-messages` |
| 커밋·작업 파일 반영 | `POST /api/v1/teams/{teamId}/git/commits:batch`, `PUT /api/v1/teams/{teamId}/git/working-files/{repoKey}` |
| 담당 Agent 추천 | `POST /api/v1/teams/{teamId}/agents/recommendations` |
| 팀 Context | `GET /api/v1/teams/{teamId}/context?part=BACKEND` |

입력·응답 필드는 [Controller](../src/main/java/com/devlink/server/controller), [DTO](../src/main/java/com/devlink/server/dto)와 [README의 문서 목록](../README.ko.md#문서)을 참고하세요. 일반 REST 응답은 `success`, `data`, `code`, `message` 형식을 사용하며 SSE·WebSocket은 별도 형식을 사용합니다.

## 설정

| 변수 | 용도 | 기본값·설정 기준 |
| --- | --- | --- |
| `DB_URL` | JDBC 주소 | 로컬 JVM은 `localhost:5432`, Compose는 내부 `postgres:5432` |
| `DB_USERNAME`, `DB_PASSWORD` | DB 인증 | 로컬 기본 `devlink`, 자체 배포 시 변경 |
| `TASK_MESSAGE_CURSOR_SECRET` | 메시지 커서 서명키 | 32바이트 이상, 필수 |
| `CORS_ALLOWED_ORIGINS` | REST 허용 Origin | 쉼표 구분, 자신의 웹 화면 주소 지정 |
| `WS_ALLOWED_ORIGINS` | WebSocket 허용 Origin | 로컬 기본 `http://localhost:3000` |
| `WORKING_FILES_REQUIRE_IF_MATCH` | 작업 파일 조건부 갱신 강제 | 기본 `false` |
| `OPENROUTER_API_KEY` | Context 추론용 OpenRouter API 키 | 기본 비어 있음, 선택 |
| `TEAM_CONTEXT_INFERENCE_BASE_URL` | OpenAI 호환 API 주소 | 기본 `https://openrouter.ai/api/v1` |
| `TEAM_CONTEXT_INFERENCE_MODEL` | 추론 모델 | 기본 `openai/gpt-6-luna` |
| `TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD` | OpenRouter 제공자 필터 | 기본 `auto`(주소가 `openrouter.ai`일 때만 적용). `true`·`false`로 고정 가능 |
| `TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY` | 추론 API 키 필수 여부 | 기본 `true`. 키 없는 자체 호스팅 서버는 `false` |
| `TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS` | 요약 출력 토큰 한도 | 기본 `6000` |
| `TEAM_CONTEXT_INFERENCE_TIMEOUT_MS` | 요약 한 번의 제한 시간 | 기본 `60000` |
| `DECISION_MODEL_ENABLED` | 담당 Agent 추천의 역할 태그 판정에 결정 모델 사용 | 기본 `false`, 선택 ([문서](agent-recommendation.md)) |
| `DECISION_MODEL_API_KEY` | 결정 모델 엔드포인트(기본 OpenRouter)로 보내는 키 | 기본 비어 있음. 비어 있으면 켜도 호출하지 않음(키 없는 자체 호스팅 설정 제외). `OPENROUTER_API_KEY`와 따로 읽으므로 같은 키를 쓰더라도 여기에도 지정 |

로컬 JVM은 `.env`를 읽고, Compose는 `app.environment`에 선언된 변수를 컨테이너로 전달합니다. 새 변수를 추가할 때는 Compose 전달 설정도 확인합니다. `WORKING_FILES_REQUIRE_IF_MATCH`와 `DECISION_MODEL_*`는 운영 Compose에서만 전달하며, 로컬 Compose에서 사용하려면 전달 설정을 추가합니다.

### 외부 LLM 요약 — 선택

기본 실행에는 외부 LLM API 키가 필요하지 않습니다. 키가 없으면 미답변 질문·실패·거절을 구조 기반으로 주요 쟁점에 반영하고, 공통 맥락·달라진 맥락은 기존 값을 유지합니다.

기본 대상은 OpenRouter의 `openai/gpt-6-luna`입니다. OpenRouter 키 한 줄을 넣으면 켜집니다.

```dotenv
OPENROUTER_API_KEY=<자신의 OpenRouter API 키>
```

다른 모델을 쓸 때만 한 줄을 더합니다.

```dotenv
TEAM_CONTEXT_INFERENCE_MODEL=openai/gpt-4o-mini
```

- 키 변수 이름은 `OPENROUTER_API_KEY`입니다. 이전 이름 `UPSTAGE_API_KEY`는 더 이상 읽지 않습니다. 그 이름으로 키를 둔 설정은 오류 없이 구조 기반 요약으로 동작하므로 이름을 옮깁니다.
- 켜졌는지는 서버 시작 로그로 확인합니다. 켜지면 `Team context inference uses openrouter.ai with model …` 줄이, 키가 비어 있으면 `OPENROUTER_API_KEY is empty, so summaries stay structural` 줄이 남습니다.
- OpenRouter 주소에는 OpenRouter 키(`sk-or-`로 시작)만 보냅니다. 다른 서비스의 키를 넣으면 그 키를 보내지 않고 구조 기반 요약으로 동작하며 시작 로그에 경고를 남깁니다. 반대로 주소를 자체 호스팅 서버로 바꿀 때는 `OPENROUTER_API_KEY`를 지웁니다. 남아 있으면 같은 이유로 구조 기반 요약으로 동작합니다.
- 기본 모델은 의견이 갈린 채 남은 작업을 요약에서 빠뜨린 경우가 있었습니다. 모델별 비교는 [팀 Context 문서](team-context.md)의 "기본 모델 선택"에 있습니다.
- 외부 API 없이 자체 vLLM 서버로 요약하려면 `docker-compose.vllm.yml`을 겹쳐 씁니다. 조건과 모델별 측정 결과는 [팀 Context 문서](team-context.md)의 "자체 호스팅 (vLLM)"에 있습니다.
- 추론 입력에는 작업·이벤트 내용과 기존 요약 등이 포함되므로 제공자의 데이터 처리 정책을 확인하고 설정합니다.

요약은 이벤트 발생 후 비동기로 갱신하며, 조회 시에는 저장된 결과를 읽습니다. 외부 호출 실패 시 구조 기반 구현으로 자동 전환하지 않고 재시도하며, 재시도 한도(기본 5회)를 넘긴 이벤트는 요약에 반영되지 않습니다. 상세는 [팀 Context 문서](team-context.md)를 참고하세요.

## 개발하기

로컬 JVM 실행과 소스 테스트에는 **JDK 21**과 실행 중인 **Docker**가 필요합니다. Gradle은 저장소의 Wrapper를 사용합니다.

빠른 시작에 따라 `.env`를 준비한 뒤 서버 컨테이너를 멈추고 DB만 실행합니다.

```sh
docker compose stop app
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

Windows PowerShell에서는 마지막 줄 대신 다음 명령을 사용합니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

빌드·테스트:

```sh
./gradlew build
```

Windows는 `.\gradlew.bat build`를 사용합니다. 테스트만 실행하려면 `build` 대신 `test`를 사용합니다. 통합 테스트는 Testcontainers로 PostgreSQL 16을 별도로 실행하며 외부 LLM 키나 배포 Secrets 없이 수행할 수 있습니다.

- 실행 JAR: `build/libs/app.jar`
- 테스트 보고서: `build/reports/tests/test/index.html`
- 소스: `src/main/java/com/devlink/server`
- DB 마이그레이션: `src/main/resources/db/migration`

### 자체 배포

HTTPS 배포 예시는 [운영 Compose](../deploy/docker-compose.prod.yml)와 [Caddy 설정](../deploy/Caddyfile)에 있습니다. 이미지 주소·API 도메인·DB 인증·서명키·허용 Origin을 자신의 환경으로 변경합니다.

제공된 운영 구성은 Caddy의 80·443 포트를 외부에 노출하고 앱은 Compose 내부에서 연결합니다. DB 호스트 포트는 `127.0.0.1:5432`에 바인딩합니다. 배포용 환경 변수는 [운영 템플릿](../deploy/.prod.env.example)을 참고하세요.

[CI/CD 워크플로우](../.github/workflows/ci-cd.yml)는 빌드·테스트와 `master` push 시 GHCR·EC2 배포를 수행합니다. 이 자동 배포 경로를 사용하려면 이미지 주소와 배포 Secrets를 자신의 환경으로 구성해야 합니다.

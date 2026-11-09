# 인증·팀·Agent 관리

3번 담당 구현과 API 사용 방법. 기존 팀원 코드의 변경을 줄이기 위해 별도 문서로 관리합니다.

## 6. 사용자 인증 API

모든 응답은 `ApiResponse(success, data, code, message)` 형식입니다. 회원가입은 자동 로그인하지 않습니다.

| 메서드 | 경로 | 요청 / 성공 응답 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | 200, `headerName`, `parameterName`, `token` |
| POST | `/api/v1/auth/signup` | `email`, `password`, `name`, 선택 `nickname` / 201, 사용자 정보 |
| POST | `/api/v1/auth/login` | `email`, `password` / 200, 사용자 정보와 세션 쿠키 |
| GET | `/api/v1/auth/users/me` | 로그인 세션 / 200, 사용자 정보 |
| POST | `/api/v1/auth/logout` | 로그인 세션과 CSRF 토큰 / 200, `data=null` |

사용자 정보에는 `userId`, `email`, `name`, `nickname`, `createdAt`을 반환하며 비밀번호·해시는 포함하지 않습니다. 이메일은 앞뒤 공백을 제거하고 소문자로 저장·조회합니다. 가입 비밀번호는 최소 8자이며 가입·로그인 모두 UTF-8 기준 72바이트 이하로 제한합니다. 이메일 중복은 `409 EMAIL_ALREADY_EXISTS`, 계정 또는 비밀번호 불일치는 `401 INVALID_CREDENTIALS`, 입력 오류는 `400 VALIDATION_FAILED`입니다.

로그인과 회원가입 중복 검사는 소문자로 변환한 입력값을 일반 동등 비교해 기존 `uk_users_email` 색인을 활용할 수 있도록 합니다. V12는 기존 대문자 이메일을 소문자로 정리하며 변환 시 중복 계정이 있으면 마이그레이션을 중단합니다. 문제 분석·이전 실험 기록·후속 측정 절차는 [이메일 조회 리팩토링 기록](performance/email-lookup-refactoring.md)에 정리했습니다.

프론트엔드는 모든 인증 요청에 `credentials: "include"`를 사용합니다.

1. `GET /api/v1/auth/csrf`로 토큰을 받고 `data.headerName`을 이름으로, `data.token`을 값으로 POST 요청 헤더에 전달합니다.
2. 회원가입 후 같은 세션과 CSRF 토큰으로 로그인합니다. 로그인 시 세션 ID와 CSRF 토큰이 교체됩니다.
3. 로그인 후 `GET /api/v1/auth/csrf`를 다시 호출해 이후 POST 요청에 사용할 토큰을 갱신합니다.
4. 새로고침 후에는 `GET /api/v1/auth/users/me`로 사용자 정보를 복원합니다. 미로그인·세션 만료 시 401입니다.
5. 갱신한 CSRF 토큰으로 로그아웃합니다. 다음 로그인 전에도 CSRF 토큰을 다시 조회합니다.

세션은 서버 메모리에 저장하며 30분 동안 요청이 없으면 만료됩니다. 세션 쿠키는 HttpOnly이며 로컬에서는 SameSite=Lax, 운영에서는 HTTPS용 Secure와 SameSite=None을 사용합니다. 운영 브라우저가 서드파티 쿠키를 차단하면 별도 사이트 간 로그인 유지가 제한될 수 있습니다. 브라우저 로그아웃은 사용자 세션만 종료하며 Agent 토큰·브로커 연결을 폐기하지 않습니다.

로그인 시 내부 `UserPrincipal(userId)`를 저장하고 권한 검사에 사용할 `userId`를 같은 세션에 기록합니다. 사용자 인증·팀 관리·Agent 등록·토큰 발급은 이 사용자 세션과 CSRF 계약을 유지합니다. 작업 생성·상태 변경·조회는 아래의 Agent Bearer 인증을 사용합니다. OAuth 외부 계정 연결과 다중 서버용 세션 저장소는 후속 범위입니다.

## 6-1. 팀 관리 및 공통 팀 권한

`POST /api/v1/teams`는 로그인한 사용자의 세션과 CSRF 토큰이 필요합니다. 요청에는 `name`만 받으며 필수·공백 불가·최대 120자입니다.

```json
{
  "name": "DevLink 팀"
}
```

성공 시 201과 공통 응답의 `data`에 `teamId`, `name`, `inviteCode`, `myRole`, `createdAt`을 반환합니다. 생성자와 역할은 요청 본문이 아닌 로그인 정보로 결정하며 `myRole`은 `OWNER`입니다. 초기 초대코드는 32바이트 난수의 Base64URL 표현으로 생성한 43자 문자열입니다.

팀과 생성자의 OWNER 가입 기록을 같은 트랜잭션으로 저장합니다. 가입 기록 저장이 실패하면 팀도 롤백합니다. DB의 초대코드 UNIQUE 충돌만 새 트랜잭션에서 최대 5회 시도하고, 다른 DB 오류는 재시도하지 않습니다. 기존 테이블과 제약을 사용하므로 마이그레이션은 추가하지 않습니다.

공통 `TeamAuthorizationService`는 서버가 확인한 로그인 사용자 ID와 대상 팀 ID를 받아 가입 정보를 반환합니다.

| 메서드 | 허용 대상 |
| --- | --- |
| `requireTeamMember(userId, teamId)` | 해당 팀의 OWNER 또는 MEMBER |
| `requireTeamOwner(userId, teamId)` | 해당 팀의 OWNER |

잘못된 사용자 ID는 401, 잘못된 팀 ID는 400, 없는 팀은 404, 미가입 사용자 또는 OWNER가 아닌 사용자의 OWNER 요청은 403으로 처리합니다. 팀 조회·초대코드 재발급·Agent 등록 등에서 공통 검사를 재사용합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | `/api/v1/teams` | 본인이 가입한 팀 목록, 최근 가입순 |
| GET | `/api/v1/teams/{teamId}` | 가입자만 팀 상세 조회 |
| GET | `/api/v1/teams/{teamId}/members` | 가입자만 팀원과 역할 조회 |
| POST | `/api/v1/teams/{teamId}/invites` | OWNER만 초대코드 재발급, 기존 코드 무효화, 201 |
| POST | `/api/v1/teams/join` | `{ "inviteCode": "..." }`로 참여, 신규 201·기존 가입 200 |

기본 팀 응답은 `teamId/name/myRole/createdAt`이며 일반 목록·상세에 초대코드를 포함하지 않습니다. 팀원 응답은 `userId/name/nickname/role/joinedAt`입니다. 초대코드는 생성·재발급 응답에서만 반환합니다. 참여 응답은 `teamId/name/myRole/joinedAt/replayed`이며 이미 가입한 OWNER의 역할을 MEMBER로 바꾸지 않습니다. 잘못되었거나 재발급으로 무효화된 코드는 404입니다. 참여와 코드 재발급은 같은 팀 행 잠금으로 직렬화합니다.

## 6-2. Agent 관리와 연결 토큰

아래 관리 API는 사용자 세션을 사용하고 변경 요청에는 CSRF 토큰이 필요합니다. 팀 Agent 목록 GET은 Agent Bearer 토큰으로도 호출할 수 있습니다.

| 메서드 | 경로 | 권한·동작 |
| --- | --- | --- |
| POST | `/api/v1/agents/teams/{teamId}` | 팀 회원이 본인 소유 Agent 등록, 201 |
| GET | `/api/v1/agents/teams/{teamId}` | 사용자 세션 또는 Agent Bearer로 팀 Agent 목록 조회 |
| GET | `/api/v1/agents/{agentId}` | 같은 팀 회원이 상세 조회 |
| POST | `/api/v1/agents/{agentId}/token` | 소유자만 토큰 발급·회전, 201 |
| DELETE | `/api/v1/agents/{agentId}` | 팀에 속한 소유자만 작업 이력이 없는 Agent 삭제, 200·data=null |

등록 요청은 `name`(필수·100자), `roleDescription`(선택·255자), `toolType`(`CLAUDE_CODE` 또는 `CODEX`), `projectName`(필수·120자)입니다. 소유자는 로그인 정보로 결정하며 등록 초기 상태는 OFFLINE입니다. Agent 프로필 수정 및 별도 토큰 폐기 API는 현재 MVP 범위에서 제외합니다.

삭제는 사용자 세션과 CSRF 토큰이 필요하며 본문은 받지 않습니다. 팀 OWNER여도 다른 사용자의 Agent를 삭제할 수 없습니다. 요청·수신 작업이 하나라도 있으면 완료 여부와 관계없이 `409 AGENT_HAS_TASKS`로 거부해 기존 작업 조회에 필요한 Agent 정보를 보존합니다. 삭제 성공 시 Agent와 토큰 해시를 제거하고 커밋 후 기존 브로커 연결·ACK 대기를 정리합니다. 이미 삭제된 Agent는 404, 삭제된 Agent의 기존 토큰은 401을 반환합니다.

조회 응답은 `agentId/teamId/ownerUserId/name/roleDescription/toolType/projectName/status/lastSeenAt/createdAt`입니다. 토큰과 해시는 포함하지 않습니다. 토큰 발급 응답의 `data`는 `agentId/token`이며 원문은 이 응답에서만 제공합니다. 토큰은 32바이트 난수로 만들고 DB에는 SHA-256 64자리 해시만 저장합니다. 한 Agent의 활성 토큰은 하나이며 재발급하면 기존 토큰을 거부합니다. 현재 MVP는 만료 시각 없이 재발급으로 토큰을 교체합니다.

토큰 재발급이 커밋된 후 기존 WebSocket 연결과 ACK 대기를 정리하고 접속 상태를 갱신합니다. 롤백된 변경으로 연결을 끊지 않습니다. 사용자 로그아웃은 브라우저의 세션과 인증·CSRF 정보를 정리하며 별도의 Agent 토큰을 무효화하지 않습니다.

## 6-3. MCP·브로커용 Agent 인증

`/ws/broker`, `/api/v1/agents/me`, `/api/v1/tasks/**`와 `GET /api/v1/teams/{teamId}/tasks`는 `Authorization: Bearer <Agent 토큰>`으로 인증합니다. 사용자 세션과 서로 대체하지 않습니다. DB의 현재 토큰 해시와 소유자의 팀 가입을 확인해 `AgentPrincipal(agentId, ownerUserId, teamId)`를 구성합니다. Agent 전용 REST는 쿠키 인증을 사용하지 않아 해당 체인에만 CSRF 예외를 적용하며, 사용자 관리 API의 CSRF 보호는 유지합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | `/api/v1/agents/me` | 토큰의 Agent 정보 확인, Agent Bearer 전용 |
| GET | `/api/v1/agents/teams/{teamId}` | 사용자 세션 또는 Agent Bearer로 팀 Agent 목록 조회 |
| POST | `/api/v1/tasks/{teamId}` | `{requestKey, receiverAgentId, prompt}`로 작업 생성 |
| PATCH | `/api/v1/tasks/{taskId}/status` | 토큰의 Agent로 작업 상태 변경 |
| GET | `/api/v1/teams/{teamId}/tasks` | 토큰의 Agent 권한으로 팀의 작업 목록 조회 |
| GET | `/api/v1/tasks/{taskId}` | 토큰의 Agent 권한으로 작업 상세 조회 |

작업 생성 경로는 `/api/v1/tasks/{teamId}`로 통일하며 `/api/v1/agent/tasks`는 사용하지 않습니다. 생성·상태 변경 시 `agentId` 쿼리를 사용하지 않고 인증된 `AgentPrincipal.agentId`를 사용합니다. 요청 사용자와 요청 Agent의 신원은 본문·쿼리가 아닌 토큰으로 결정합니다. 작업 생성 본문의 `requestKey`, `receiverAgentId`, `prompt`와 기존 작업 생성 서비스를 유지합니다.

`TaskRequestAuthorizationAdvice`는 인증된 요청 Agent를 기준으로 `TaskAuthorizationService.requireRequester`를 호출해 팀 가입·요청 Agent 소유권·송수신 Agent의 팀 일치를 확인합니다. 생성 URL의 팀과 토큰의 팀이 다르면 403이며, 수신 Agent가 없거나 다른 팀에 속하면 404입니다. 권한 조회와 작업 저장은 별도 트랜잭션입니다. 상태 변경·조회는 각 담당 서비스의 기존 권한·상태 규칙을 유지합니다.

`GET /api/v1/agents/me`는 토큰에서 Agent·소유자·팀을 결정하며 별도의 agentId를 받지 않습니다. 팀 목록 GET은 Authorization 헤더가 있으면 Agent 인증, 없으면 사용자 세션 인증을 사용합니다. 잘못된 토큰·헤더는 유효한 사용자 세션이 있어도 401로 거부하며, 토큰의 팀과 URL의 팀이 다르면 소유자가 양쪽 팀에 가입했어도 403으로 거부합니다. 두 인증 방식의 목록 응답은 동일합니다.

기존 `/api/v1/agent/context`와 `/api/v1/agent/agents`는 제거했습니다. CLI·MCP 담당자는 본인 확인을 `/api/v1/agents/me`, 목록 조회를 `/api/v1/agents/teams/{teamId}`로 전환해야 합니다. client 코드는 별도 수정 대상이며, 목록의 teamId는 저장된 검증 정보 또는 본인 확인 응답의 `data.teamId`를 사용합니다.

## 6-4. 최신 CLI의 사용자·팀·Agent 준비 흐름

`DevLink_client`의 Rust CLI는 사용자 세션, 중앙 Agent 토큰, 로컬 MCP 자격정보를 구분해 관리합니다. 회원가입은 사용자 인증 API나 프론트엔드에서 먼저 수행하며 CLI의 `auth` 명령은 로그인·상태 확인·로그아웃을 제공합니다.

1. `devlink auth login --email <이메일>`: `GET /api/v1/auth/csrf` 후 `POST /api/v1/auth/login`을 호출합니다. 비밀번호는 숨김 입력 또는 표준 입력으로 받고 명령행 인자로 받지 않습니다.
2. 로그인 직후 `/api/v1/auth/users/me`로 신원을 확인하고 회전된 `JSESSIONID`를 로컬 비공개 세션 파일에 저장합니다. CSRF 토큰도 다시 조회하며 이후 POST 직전에 새로 조회합니다. 팀·Agent 관리 호출은 이 세션을 사용합니다.
3. `devlink team create --name <이름>` 또는 `devlink team join`: 팀 생성이나 초대코드 참여 API를 호출합니다. 생성·참여 후 팀 상세와 팀원 목록으로 현재 사용자의 가입 정보를 확인합니다.
4. `devlink team select --id <팀 ID>`: 서버에서 팀·가입 정보를 확인한 뒤 선택한 팀을 로컬에 저장합니다. 별도의 팀 선택 REST API를 호출하지 않습니다.
5. `devlink agent create --name <이름> --tool <codex|claude-code> --project-name <표시 이름> --project <로컬 폴더>`: 선택한 팀에 Agent를 생성한 뒤 토큰 발급 API를 별도로 호출합니다. 등록과 토큰 발급은 두 API이며 등록 응답 자체에는 토큰이 없습니다.
6. 발급한 토큰을 `/api/v1/agents/me`로 검증하고 Agent·사용자·팀·로컬 작업 폴더·토큰을 로컬 비공개 프로필에 저장합니다. 기존 Agent는 `devlink agent use --id <Agent ID>`로 소유권을 확인해 선택할 수 있으며, 필요한 토큰 발급은 `devlink agent token`으로 명시적으로 수행합니다.
7. `devlink agent status`와 브로커 시작 시 저장된 Agent 토큰을 다시 검증합니다. 사용자 로그아웃은 사용자 세션을 종료하며 별도의 Agent 토큰을 폐기하지 않습니다.

Agent 생성 결과가 불명확하면 CLI가 자동으로 다시 생성하지 않습니다. `agent list`로 서버 상태를 확인하고 기존 Agent ID를 선택하는 흐름을 사용합니다. `projectName`은 표시 정보이며 실제 작업 폴더는 로컬 프로필의 경로로 지정합니다.

## 6-5. MCP 도구 실행부와의 구분

`register_agent`·`list_agents`의 TypeScript 도구 정의와 별도 중앙 REST 클라이언트는 `DevLink_client` 저장소에 있습니다. `packages/mcp-server/AGENT_MANAGEMENT.md`의 별도 모듈은 최초 호출에서 사용자 세션과 CSRF로 등록·토큰 발급을 수행하는 흐름을 설명하지만, 현재 MCP 공통 실행부에 자동으로 연결되어 있지는 않습니다.

최신 CLI의 준비 흐름은 위와 같이 MCP 호출 전에 Agent 등록·토큰 발급·로컬 프로필 저장을 수행합니다. 따라서 최초 `register_agent` 호출이 반드시 새로운 Agent를 생성한다고 가정하면 안 됩니다. CLI로 준비한 Agent와 MCP 세션을 어떻게 연결할지는 실제 도구 등록·호출 경로를 담당하는 1·2번 담당자의 연동 범위입니다. 이번 백엔드 인증 변경은 MCP 실행부나 도구 등록 코드를 변경하지 않습니다. 토큰은 모델의 도구 입력·출력에 포함하지 않습니다.

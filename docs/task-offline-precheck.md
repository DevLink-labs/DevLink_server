# 작업 저장 전 오프라인 검사 (S15P31D108-67)

신규 `POST /api/v1/tasks/{teamId}` 요청은 수신자의 존재·팀 확인 후, DB 상태가 ONLINE이며 같은 Agent·소유자·팀으로 인증된 현재 열린 브로커 연결이 있는지 확인한다. 오프라인·닫힌 연결·이전 ONLINE 상태만 남은 경우 `409 RECEIVER_OFFLINE`으로 거부하고 Task/CREATED를 저장하지 않는다. 신원 불일치는 `403 ACCESS_DENIED`, 연결 registry 내부 오류는 `503 DELIVERY_SERVICE_UNAVAILABLE`이다.

사전 검사는 메모리의 현재 연결과 인증 principal만 읽는다. 소켓 송신·ACK 등록·presence 변경은 하지 않는다. 검사 이후 단절되는 경합은 여전히 가능하므로 실제 전송은 기존대로 생성 커밋 후 수행한다. 저장 후 전달 실패는 PENDING·taskId·전달 결과를 보존하고 자동 재전송하지 않는다.

동일 requester Agent/requestKey의 재접수는 사전 검사 전에 기존 작업을 반환한다. 수신자가 나중에 오프라인이 되어도 이미 저장된 작업이 사라지거나 다시 생성·전송되지 않는다. 다른 payload로 같은 키를 재사용하면 기존 REQUEST_KEY_CONFLICT 검증을 유지한다.

## 검증

- `TaskRoutingServiceTest`: 열린 현재 연결의 신원 확인, OFFLINE, stale ONLINE, 닫힌 연결, 다른 Agent/소유자/팀, 조회 중 연결 교체, registry 오류. 사전 검사에서 송신·ACK·DB 변경이 없음을 확인한다.
- `TaskCreationIntegrationTest`: 사전 검사 거부 시 Task와 이벤트 미저장, 단절 후 동일 키 재접수에서 기존 결과 반환, 생성 이력 실패 시 롤백과 송신 없음. 기존 커밋 후 전달·ACK·동시 생성 멱등성 시험을 유지한다.
- `AgentTokenIntegrationTest`와 `AuthIntegrationTest`의 생성 성공 시험은 수신자의 실제 인증 브로커를 연결하고 ONLINE으로 보고한다. `TaskRoutingIntegrationTest`에서는 READY만 받은 연결과 stale ONLINE을 실제 서비스/DB로 거부하며, HTTP 시험은 409와 Task/CREATED 미저장을 대조한다.
- 표준 실행: Java 21과 Docker에서 `./gradlew test`로 전체 회귀를 실행한다.
- 2026-11-02 로컬에서는 Docker daemon이 없어 표준 Testcontainers 전체 실행을 통과했다고 주장하지 않는다. 별도 ignored Gradle init harness에서 테스트 기반 클래스의 DB 공급만 portable PostgreSQL 17.11 전용 DB로 바꾸고 같은 저장소 테스트 본문을 실행했다. 이 대체 환경과 표준 PostgreSQL 16/Testcontainers 실행은 구분한다. 최종 결과·실제 모델 ID와 PR은 당일 Notion 검증 기록에 남긴다.

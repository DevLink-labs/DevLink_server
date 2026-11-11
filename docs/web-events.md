# 웹 실시간 이벤트

`GET /api/v1/web/events`는 로그인 세션의 `ROLE_USER`만 구독할 수 있는 SSE 연결이다. AgentToken 인증 경로는 변경하지 않는다. 브라우저는 세션 쿠키를 보내며 다른 출처에서는 `EventSource(url, { withCredentials: true })`를 사용한다.

연결당 다음 이름의 이벤트를 제공한다.

| 이벤트 | 데이터 |
| --- | --- |
| `ready` | `{}`. 최초 연결과 재연결에서 REST 캐시를 동기화하는 신호 |
| `subscription-replaced` | `{}`. 사용자별 연결 상한으로 구독이 교체됨. 클라이언트는 EventSource를 닫고 폴링으로 전환 |
| `task-updated` | `{ task, event }`. `task`는 기존 웹 작업 상세 응답이며 `event`는 기존 웹 대화 이벤트 또는 `null` |
| `agent-presence` | `{ teamId, agentId, status, lastSeenAt, deleted, agent }`. `agent`는 기존 Agent 응답이며 삭제 시 `null` |

작업·Agent·팀 멤버 변경을 저장한 트랜잭션이 커밋된 이후에만 전송한다. 각 전송 시점의 팀 멤버십을 확인하여 기존 웹 조회 권한과 동일한 범위로 제한한다. 내부 ACK 이벤트는 대화 본문으로 전송하지 않는다. 연결은 사용자당 기본 6개로 제한하며 초과하면 가장 오래된 연결을 종료한다. 느린 연결로 전송 큐가 가득 차거나 쓰기가 실패하면 연결을 종료하고 클라이언트 재연결 시 REST 동기화로 복구한다.

기본 15초마다 heartbeat 주석을 전송한다. 로그아웃·세션 만료·세션 ID 변경 시 해당 연결을 종료하고, 팀 멤버 변경 시 해당 사용자의 연결을 종료하여 재연결 후 권한과 캐시를 다시 확인한다. 역방향 프록시는 응답 버퍼링을 끄고 heartbeat보다 긴 읽기 제한 시간을 설정해야 한다. 응답에는 `X-Accel-Buffering: no`와 `Cache-Control: no-cache, no-transform`을 지정한다.

## 누락 기록 복구

현재 SSE는 단일 서버 프로세스의 연결에 전달하며 영속적인 SSE 재생 로그는 제공하지 않는다. `Last-Event-ID`만으로 누락 복구를 보장하지 않는다. 모든 `ready`에서 REST 조회를 통해 누락분과 작업 상태를 다시 동기화해야 한다.

`GET /api/v1/web/teams/{teamId}/tasks/{taskId}/events?afterSequence=123&limit=50`은 지정한 순번 뒤의 웹 공개 이벤트를 오름차순으로 반환한다. `afterSequence=0`도 허용한다. `beforeSequence`와 함께 보낼 수 없다. `hasNext`가 참이면 `nextAfterSequence`를 다음 요청의 `afterSequence`로 사용한다. 기존 과거 기록의 `beforeSequence` 조회는 유지한다. 순번·ID는 문자열로 취급하고 중복 이벤트를 제거해야 한다.

다중 서버를 운영할 때에는 다른 인스턴스의 커밋도 구독 서버로 전달하는 공유 브로커가 필요하다. 외부 SQL 변경이나 다른 서버 프로세스의 변경은 현재 인스턴스의 JPA 이벤트를 발생시키지 않는다.

## 확인

세션 인증·팀 권한·연결 종료·전송 순서·커밋·롤백·증분 조회와 실제 로그인 후 SSE 수신을 다음 명령으로 검증한다. 통합 테스트 실행에는 Docker가 필요하다.

```powershell
.\gradlew.bat test --tests com.devlink.server.service.WebEventStreamServiceTest --tests com.devlink.server.service.WebEventQueryServiceTest --tests com.devlink.server.WebEventIntegrationTest
```

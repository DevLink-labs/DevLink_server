# 질문·답변 WebSocket 알림

## 동작

1. 질문·답변 POST가 TaskEvent와 Outbox를 같은 트랜잭션에서 저장한다.
2. 커밋된 Outbox를 기존 OutboxPublisher가 조회하고 짧은 트랜잭션으로 점유한다.
3. TaskNotificationService가 원본 이벤트와 수신자를 검증한다.
4. 저장된 receiver_agent_id의 현재 WebSocket으로 Outbox JSON을 그대로 보낸다.
5. 브로커는 알림을 신호로 사용해 GET /api/v1/agents/me/task-messages를 즉시 조회한다.

DB 스키마, 기존 V7 마이그레이션, 질문·답변 POST의 저장 형식은 변경하지 않는다.
상태 변경·완료 알림은 기존처럼 Task 요청자에게 전송한다.

## 알림 계약

```json
{
  "event_id": "d339c442-5a5f-44db-9e9b-127309187c21",
  "type": "TASK_QUESTION_CREATED",
  "task_id": 100,
  "receiver_agent_id": 2,
  "payload": {
    "task_event_id": "502",
    "sequence": "13",
    "event_type": "QUESTION"
  }
}
```

- 질문: type=TASK_QUESTION_CREATED, payload.event_type=QUESTION.
- 답변: type=TASK_ANSWER_CREATED, payload.event_type=ANSWER.
- event_id는 Outbox UUID다. 재시도 시 같은 값과 같은 JSON을 보낸다.
- payload.task_event_id는 원본 TaskEvent.id의 문자열이다. REST 응답의 eventId와 같은 값이다.
- payload.sequence는 해당 Task 안의 이벤트 순서를 나타내는 양의 정수 문자열이다.
- receiver_agent_id는 outbox_events의 별도 컬럼이 아닌 message JSON의 필드다.
- 본문은 포함하지 않는다. 브로커는 알림 수신만으로 cursor를 변경하거나 작업을 실행하지 않는다.
- 알림 도착 순서나 개수로 이벤트 순서를 추정하지 않는다. Pull 결과의 sequence와 hasNext를 따른다.
- 질문·답변 알림은 Task 상태 변경이 아니므로 version 필드가 필요하지 않다.

## 전송 검증

- 공통 Outbox ID·타입·Task ID·destination·messageKey가 저장 정보와 일치해야 한다.
- 원본 이벤트가 존재하며 Task ID·이벤트 종류·sequence가 알림과 일치해야 한다.
- 수신자는 원본 작성자의 상대 Task 참여자여야 한다. 자기 배정·작성자 자신·제삼자는 거부한다.
- 수신 Agent가 같은 팀에 있고 소유자가 현재 팀원이어야 한다.
- 요청자에게 보내는 경우 Task에 기록된 요청자 소유자와도 일치해야 한다.
- 실제 소켓의 Agent·소유자·팀 인증 정보가 현재 수신 Agent와 일치해야 한다.
- 이후 Task가 종료되어도 이미 저장한 질문·답변 알림은 발행한다.
- 기존 상태·완료 알림의 version 검증은 유지한다.

## 실패와 복구

기존 Outbox의 점유·점유 연장·지수 재시도·시도 횟수 제한을 그대로 사용한다.
기본 발행 조회 간격은 1초이며 브로커의 안전망 Pull 주기와 별개다.

- 연결 없음·연결 교체·전송 오류: 기존 정책으로 재시도한다.
- 수신 Agent의 권한·소켓 인증 정보 불일치: RECIPIENT_UNAVAILABLE로 재시도한다.
- 형식·원본 참조·수신 대상 불일치: INVALID_EVENT로 FAILED 처리한다.
- 재시도 상한 도달: FAILED이며 자동으로 다시 시작하지 않는다.
- PUBLISHED는 소켓 쓰기 성공이며 브로커 ACK나 로컬 DB 저장 완료를 뜻하지 않는다.
- 알림이 중복되거나 전달되지 않아도 브로커는 재접속·안전망 Pull로 원본을 복구한다.
- task-messages는 Outbox의 존재·발행 상태와 관계없이 task_events에서 조회한다.

## 검증

TaskMessageNotificationServiceTest에서 양방향 수신자 선택, version 없는 봉투, 잘못된 ID·순서·타입,
원본 이벤트·수신자 불일치, 인증 정보 불일치, 팀 탈퇴, 수신 소켓 오류 정리를 검증한다.

TaskNotificationIntegrationTest에서 실제 PostgreSQL·WebSocket·HTTP로 질문·답변 전송과 Pull,
멱등 재시도, 오프라인 재시도, FAILED 상태 복구, 종료 Task, 잘못된 수신자 차단,
커밋 전 비공개·롤백을 검증한다. 기존 상태·완료 알림과 점유 복구 테스트도 유지한다.

```powershell
.\gradlew.bat test --tests com.devlink.server.service.TaskMessageNotificationServiceTest --tests com.devlink.server.TaskNotificationIntegrationTest
```

Windows에서 Bad address: listen 오류가 발생하면 테스트 JVM에만
`-Djava.net.preferIPv4Stack=true`를 적용하고 필요하면 `server.address=127.0.0.1`로 바인딩한다.

### 검증 결과 (2026-11-03)

- 알림 단위·통합, 질문·답변 POST, 동기화 관련 테스트 100개 통과.
- 전체 646개 실행에서 638개 통과. 팀 관리 테스트 8개는 Windows의 Bad address: listen으로
  테스트 서버 초기화에 실패했으며, 해당 클래스만 별도로 재실행해 8개 모두 통과했다.
- 새 알림 테스트는 단위 30개와 통합 8개다. 기존 상태·완료 알림 회귀 테스트도 통과했다.
- Windows 보정은 Git에서 제외된 build/tmp의 테스트 실행 스크립트에만 적용했다.
- DB 마이그레이션 변경 없음. git diff --check 통과.

# 질문·답변 도착 정보 동기화

## develop V7 호환 범위

develop의 `V7__extend_task_events.sql`과 `TaskEvent.of(...)`를 그대로 사용한다.
추가 마이그레이션, 수신함 테이블, 동기화용 컬럼, DB 서명키 테이블을 만들지 않는다.
질문·답변은 `author_agent_id`, `question_event_id`, `request_key` 계약에 맞춰 저장한다.
작성자·requestKey는 QUESTION/ANSWER에 필수이고, ANSWER의 질문 참조도 필수다.

## API 계약

`GET /api/v1/agents/me/task-messages?cursor={nextCursor}&limit=50`

- Agent Bearer 토큰이 필요하며 사용자 로그인 세션으로 대체할 수 없다.
- `limit`은 기본 50, 허용 범위 1~100이다. cursor 생략 시 처음부터 조회한다.
- 요청 본문은 없으며 성공은 200이다. 빈 목록에서도 유효한 nextCursor를 반환한다.
- 오류는 공통 응답의 400 VALIDATION_FAILED, 401 AUTHENTICATION_REQUIRED,
  403 ACCESS_DENIED, 405 METHOD_NOT_ALLOWED, 500 INTERNAL_SERVER_ERROR를 사용한다.
- items는 eventId(원본 TaskEvent.id 문자열), taskId, sequence(문자열),
  eventType(QUESTION/ANSWER), createdAt(UTC)만 포함한다. notificationId는 없다.
- 본문·작성자·질문 참조는 기존 공통 상세 조회 담당 범위다.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "eventId": "502",
        "taskId": 100,
        "sequence": "13",
        "eventType": "ANSWER",
        "createdAt": "2026-11-03T05:30:00Z"
      }
    ],
    "nextCursor": "서버가 발급한 불투명한 문자열",
    "hasNext": false
  },
  "code": null,
  "message": null
}
```

## 수신 대상과 부작용

인증된 Agent가 현재 팀의 Task 참여자이고, 작성자가 그 Task의 상대 참여자인 경우만 반환한다.
본인 작성, 자기 배정 Task의 자기 질문, 비참여 Agent 작성, 다른 팀 이벤트는 제외한다.
같은 질문의 여러 답변은 독립된 eventId로 반환한다.

조회 원본은 task_events와 tasks다. outbox가 없거나 PENDING/PUBLISHED/FAILED여도 복구한다.
Task가 종료되어도 조회 권한이 유지되면 기존 이벤트를 반환한다. GET으로 Task 상태·version·시각,
이벤트·outbox·읽음 상태를 변경하지 않는다.

## Task별 cursor와 늦은 커밋

전역 마지막 eventId를 사용하지 않고, Task마다 실제 반환한 마지막 sequence를 기억한다.
예를 들어 내부 진행 위치가 `{100: 5, 200: 3}`이면 Task 100은 5번 이후, 200은 3번 이후,
처음 보는 Task는 0번 이후를 조회한다. 클라이언트는 이 내부 형식을 해석하지 않는다.

develop V7은 같은 Task의 이벤트 INSERT를 부모 행의 FOR NO KEY UPDATE 잠금으로 직렬화한다.
따라서 같은 Task의 sequence 6이 미커밋인데 7이 먼저 커밋되는 상황은 생기지 않는다.
다른 Task의 이벤트가 먼저 커밋돼도 그 Task의 위치만 갱신하므로 늦은 커밋을 건너뛰지 않는다.
이 보장을 유지하려면 기존 V7 트리거와 이벤트 추가 전용 저장 계약을 유지해야 한다.

- cursor의 Task 위치는 실제 응답한 항목만으로 갱신한다. 빈 조회는 위치를 진행하지 않는다.
- 페이지 사이 새 커밋도 다음 조회 대상에 포함할 수 있다. 고정된 과거 스냅샷 창은 사용하지 않는다.
- 한 Task 안에서는 sequence 오름차순으로 반환한다.
- 마지막 반환 Task 다음부터 순환해 조회하므로 한 Task의 연속 메시지가 다른 Task를 막지 않는다.
- 여러 Task 전체의 생성 시각 순서는 보장하지 않는다. hasNext=true이면 즉시 추가 조회한다.
- cursor는 gzip 압축 후 HMAC-SHA256 서명을 붙인다. Agent·사용자·팀에 바인딩한다.
- 다른 Agent의 정상 cursor는 403, 변조·손상·이전 버전 cursor는 400이다.
- 구버전 cursor를 가진 브로커는 cursor 없이 재동기화한 뒤 eventId로 중복을 제거한다.
- Task 수에 따라 cursor가 커진다. 토큰 64KiB, 압축 해제 내용 1MiB를 제한하며 위치를 임의로
  버려 누락을 만들지 않는다. 많은 Task를 지원할 때는 HTTP 요청 길이와 cursor 크기를 함께 검토한다.

## 실행 설정

`TASK_MESSAGE_CURSOR_SECRET`에 32바이트 이상의 무작위 비밀 값을 설정한다.
서버 재시작·여러 인스턴스에서 동일한 값을 유지한다. 없거나 너무 짧으면 시작 시 오류로 알린다.
매 실행마다 임시 키를 만들지 않는다. 키를 교체하면 기존 cursor를 사용할 수 없어 재동기화가 필요하다.

로컬은 프로세스 환경변수 또는 기존 `.env` 로딩 경로로 지정한다. 운영은 GitHub Secret
`PROD_ENV`에 이 키를 추가한다. Compose의 app.environment에도 전달 경로를 추가했다.
예제 파일에는 실제 키를 넣지 않으며, test 프로필에만 테스트 전용 고정 키가 있다.
DB 자체나 V1~V7 SQL은 수정하지 않는다.

## 브로커 연동

- 접속·재접속, WebSocket 신호 수신, 느슨한 안전망 주기마다 같은 GET API를 호출한다.
- 안전망 초기 제안은 60초 ±10초이며 브로커 설정값이다.
- 도착 정보와 nextCursor를 로컬 DB에 함께 저장하고 실패하면 기존 cursor로 재요청한다.
- eventId로 중복을 제거하고, 실행 중 새 Pull 신호는 모아서 처리한다.
- 로컬 보관은 Agent 읽음·답변·작업 재개와 별개다. 본문은 get_task_result로 확인한다.

질문·답변 POST, Q&A outbox 저장·발행, WebSocket 신호 및 브로커 타이머는 별도 연동 범위다.

## 검증

TaskMessageSyncIntegrationTest에서 실제 PostgreSQL과 HTTP로 수신 범위·복수 답변·빈 결과·분할·재조회,
큰 ID 선커밋/작은 ID 후커밋, 페이지 중 늦은 커밋, 빈 조회 후 커밋, 롤백, 인증·권한·변조·405,
조회 전후 업무 데이터 보존을 확인한다. 답변 테스트도 실제 질문 참조와 requestKey를 저장한다.
독립 스키마에서 V6 데이터를 develop V7로 이관하는 테스트도 포함한다.

```powershell
.\gradlew.bat test --tests com.devlink.server.TaskMessageSyncIntegrationTest
```

Windows의 Bad address: listen 오류가 발생하는 환경에서는 테스트 JVM에만
`-Djava.net.preferIPv4Stack=true`를 적용한다. 운영 소켓 설정은 변경하지 않는다.

### develop 병합 후 검증 결과 (2026-11-03)

- 전체 599개 테스트 통과. 실패·오류·건너뜀 0건. 그중 동기화 통합 테스트 24개 통과.
- 1,000개 Task의 개별 진행 위치도 압축 cursor로 보존하고 다른 codec 인스턴스에서 복원했다.
- V1~V7 마이그레이션은 origin/develop의 64802c4와 동일하다. 새로운 DB 변경은 없다.
- 충돌 표시 제거, 공통 모델 검증·질문 참조·requestKey 보존, 기존 기능 회귀 검증 완료.

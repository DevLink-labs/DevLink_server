# 팀 Context 정합성 개선 과제

- 작성일: 2026-11-11
- 검토 기준: `adad96a` (`fix: merge conflict`)
- 상태: 미해결 / 추후 개선 예정
- 범위: 팀·파트 Context 추론 응답 검증, 이벤트 입력 구성, 구조 신호 기반 요약의 쟁점 개수 제한

## 1. 검토 결과와 현재 영향

| ID | 우선순위 | 개선 과제 | 발생 조건 | 영향 |
| --- | --- | --- | --- | --- |
| CTX-C01 | P2 | 추론 응답의 필수 필드 검증 | LLM이 `{}` 등 필수 필드가 누락된 JSON을 반환 | 최신 Context가 빈 요약으로 바뀔 수 있음 |
| CTX-C02 | P2 | 이벤트 발생 시점 기준으로 추론 입력 구성 | Outbox 처리가 지연되는 동안 작업 상태·질문·답변이 추가됨 | 과거 이벤트에 이후 상태와 완료 결과·답변이 섞임 |
| CTX-C03 | P2 | 최신 쟁점을 고려한 개수 제한 | 기존 쟁점이 설정 상한인 기본 20개에 도달 | 새 질문·실패·거절 쟁점이 최신 요약에서 누락될 수 있음 |

이 세 항목 자체는 컴파일이나 서버 기동을 차단하는 문제가 아니다. 일반적인 짧은 시연에서도 항상 발생하는 것은 아니지만 조건에 따라 Context 표시 내용이 부정확해질 수 있다. 전체 서비스 실행·시연의 성공 여부까지 검증했다는 의미는 아니다.

## 2. CTX-C01: 추론 응답의 필수 필드 누락을 정상 요약으로 처리

### 관련 코드

- [OpenAiCompatibleTeamContextInferenceClient.java](../src/main/java/com/devlink/server/client/OpenAiCompatibleTeamContextInferenceClient.java): `infer`
- [TeamContextSummary.java](../src/main/java/com/devlink/server/dto/teamcontext/TeamContextSummary.java): 생성자, `withoutNulls`
- [TeamContextStore.java](../src/main/java/com/devlink/server/service/TeamContextStore.java): `save`

### 현재 동작과 재현

1. 모델 응답 본문에 `common`, `changed`, `issues`가 없는 `{}`를 넣는다.
2. Jackson 역직렬화 시 누락된 필드는 `null`로 전달된다.
3. `TeamContextSummary` 생성자가 각 `null`을 빈 목록으로 바꾼다.
4. 클라이언트는 정상 추론 결과로 반환하고 저장 경로는 세 배열이 빈 새 버전을 저장할 수 있다.

기존 버전 이력이 삭제되는 것은 아니지만 최신 요약 조회에서는 이전 내용이 사라진 것처럼 보인다. 요청에 JSON Schema를 지정하더라도 서버의 응답 검증은 별도로 필요하다.

### 개선 방향

- 모델 응답을 DTO로 바꾸기 전에 JSON 객체 여부와 세 필수 필드의 존재·배열 타입을 검증한다.
- 누락·`null`·타입 오류는 `TeamContextInferenceException`으로 처리해 기존 Outbox 재시도 흐름을 사용한다.
- 검증 실패 시 새 Context 버전을 저장하거나 Outbox를 `PUBLISHED`로 처리하지 않는다.
- 세 필드가 모두 명시된 정상 빈 응답(`{"common":[],"changed":[],"issues":[]}`)과 필드 누락을 구분한다.

### 완료 기준

- [ ] `{}`, 필드 일부 누락, 필드 값 `null`, 배열 대신 문자열을 반환하는 응답을 거부한다.
- [ ] 잘못된 응답 이후에도 기존 최신 Context 버전과 내용이 유지된다.
- [ ] 검증 실패가 재시도 대상으로 기록되고 재시도 한도·실패 정책이 유지된다.
- [ ] 필수 필드를 갖춘 정상 응답과 정상 빈 배열 응답은 기존대로 처리한다.

## 3. CTX-C02: 과거 이벤트에 이후 상태·완료 결과·답변이 혼입

### 관련 코드

- [TeamContextStore.java](../src/main/java/com/devlink/server/service/TeamContextStore.java): `prepare`, `eventText`
- [TeamContextOutboxWriter.java](../src/main/java/com/devlink/server/service/TeamContextOutboxWriter.java): `enqueue`
- [TeamContextInferenceRequest.java](../src/main/java/com/devlink/server/dto/teamcontext/TeamContextInferenceRequest.java): `NewEvent`, `Hints`

### 현재 동작과 재현

1. 작업을 시작하고 질문·답변·완료를 등록하되 Context 소비는 뒤늦게 수행한다.
2. 소비기가 과거의 `IN_PROGRESS` 상태 변경 이벤트를 처리한다.
3. `prepare`는 현재 Task 상태를 읽으므로 `taskStatus`를 `COMPLETED`로 구성한다.
4. `eventText`는 해당 과거 상태 변경 이벤트에도 현재 Task의 완료 결과를 붙인다.
5. 질문·답변 집계는 해당 Task의 전체 이력을 읽으므로 대상 이벤트 이후의 질문·답변까지 포함한다.

이벤트 처리 순서를 지켜도 추론 입력에는 미래 정보가 섞일 수 있다. 같은 이벤트라도 처리 시점에 따라 입력이 달라져 버전 이력의 해석과 재처리 결과 비교가 어려워진다.

### 개선 방향

- 이벤트 발생 시점의 상태·결과 등 변경 가능한 값을 이벤트 또는 Outbox에 스냅샷으로 기록한다.
- 질문·답변 집계와 미답변 질문 목록은 처리 대상 이벤트의 `sequence` 이하 이력만 사용한다.
- 완료 결과는 완료를 기록한 이벤트의 입력에만 포함한다.
- 기존 스냅샷 없는 Outbox의 호환 처리와 이미 생성된 요약의 재생성 필요 여부를 함께 정한다.

### 완료 기준

- [ ] 이벤트를 쌓아 둔 뒤 일괄 처리해도 각 이벤트에 당시 상태가 전달된다.
- [ ] 과거 시작 이벤트의 입력에 이후 완료 결과가 포함되지 않는다.
- [ ] 질문 이벤트를 처리할 때 이후 답변 때문에 미답변 질문이 미리 제거되지 않는다.
- [ ] 동일한 이전 요약과 대상 이벤트로 입력을 구성하면 처리 시점이 달라도 동일한 입력이 만들어진다. LLM 출력의 동일성까지 요구하는 것은 아니다.
- [ ] 기존 Outbox 호환 처리와 재시도·중복 방지·버전 충돌 처리가 유지된다.

## 4. CTX-C03: 쟁점 상한에 도달하면 새 쟁점 누락

### 관련 코드

- [StructuralTeamContextInferenceClient.java](../src/main/java/com/devlink/server/client/StructuralTeamContextInferenceClient.java): `infer`
- [TeamContextSummary.java](../src/main/java/com/devlink/server/dto/teamcontext/TeamContextSummary.java): `normalized`

### 현재 동작과 재현

1. 이전 요약에 다른 작업의 미해결 쟁점 20개를 넣는다.
2. 새 작업에서 미답변 질문을 등록한다.
3. 구조 신호 기반 클라이언트는 기존 쟁점 뒤에 새 질문 쟁점을 추가한다.
4. `normalized`는 목록 앞에서부터 최대 20개만 남기므로 새 질문 쟁점이 빠진다.

같은 방식으로 뒤에 추가되는 실패·거절 쟁점도 누락될 수 있다. 원본 질문·이벤트는 남지만 최신 Context가 최근 문제를 보여주지 못한다. 기본 설정은 20개이며 실제 상한은 `devlink.team-context.max-items`를 따른다.

### 개선 방향

- 개수 제한 전에 해소된 쟁점 제거, 중복 제거, 최신성·중요도에 따른 보존 순서를 적용한다.
- 새 쟁점을 우선하되 어떤 기존 항목을 제외할지 명시적인 정책을 정한다.
- 정렬과 제한을 적용해도 질문의 근거 좌표인 `evidence`와 답변 후 해소 처리가 유지되게 한다.

### 완료 기준

- [ ] 기존 쟁점이 상한에 도달해도 새 질문 쟁점이 보존된다.
- [ ] 새 실패·거절 쟁점도 정한 우선순위에 따라 보존된다.
- [ ] 저장되는 쟁점 수는 설정 상한을 넘지 않는다.
- [ ] 중복 쟁점이 늘어나지 않고 답변이 등록된 질문의 해소 처리가 유지된다.
- [ ] 동일한 입력에서 보존·제외되는 항목이 일관되게 결정된다.

## 5. 검증 기록과 후속 작업

- 리뷰 당시 저장된 Gradle XML 보고서: 67개 테스트 클래스, 총 1,459개 테스트, 실패 0개, 오류 0개, 건너뜀 0개.
- 위 수치는 기존 실행 보고서의 집계이며 리뷰 중 전체 테스트를 새로 실행한 결과는 아니다.
- 관련 현재 소스를 별도로 컴파일하고 독립 재현 코드로 아래 동작을 확인했다.

| 재현 항목 | 확인 결과 |
| --- | --- |
| `{}` 역직렬화 및 정규화 | 정상적인 빈 요약으로 변환됨 |
| 기존 쟁점 20개 + 새 질문 | 최종 20개 중 새 질문의 근거 좌표가 없음 |
| 과거 시작 이벤트를 작업 완료 후 처리 | 입력 상태 `COMPLETED`, 이후 완료 결과 포함, 이후 답변 수 1개 포함 |

추후 수정 시 위 완료 기준을 회귀 테스트로 추가하고 전체 테스트를 다시 실행한다. 시연에서는 Context 갱신을 확인한 뒤 다음 동작으로 진행하면 CTX-C02의 발생 가능성을 낮출 수 있지만 이는 코드 수정과 검증을 대체하지 않는다.

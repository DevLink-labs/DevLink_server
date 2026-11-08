# 팀·파트 간 Context 중계 구현 현황

`GET /api/v1/teams/{teamId}/context?part=&counterpart=`. Agent 연결 토큰으로 인증하는 읽기 전용 조회이며, 설계 메모(팀·파트 간 Context 중계 설계)의 개념 정의와 결정 사항을 따른다. 중앙 서버가 작업 요청·상태 변경·완료·질문·답변을 지켜보며 파트 쌍마다 **공통 맥락(common)·달라진 맥락(changed)·주요 쟁점(issues)** 요약을 버전으로 쌓고, Agent는 세션 시작과 `create_task` 직전에 이를 읽는다.

## 실행 흐름

1. `TaskCreationService`·`TaskStatusService`·`TaskCompleteService`·`TaskMessageService`가 `TaskEvent`를 저장한 뒤 같은 트랜잭션에서 `TeamContextOutboxWriter.enqueue(task, event)`를 호출한다. `outbox_events`에 작업·이벤트 ID, 요청자·수신자 파트, 파트 쌍의 처리 순번을 저장한다. 순번 할당과 커밋은 같은 파트 쌍 안에서 직렬화한다.
2. `TeamContextUpdater`는 전용 스케줄러에서 `devlink.team-context.poll-interval-ms` 간격으로 행을 점유한다. 같은 쌍의 선행 행이 점유 또는 재시도 대기 중이면 후속 행을 대기시킨다. Context 점유 시간은 추론 제한에 30초를 더한 값이며, 재시도 간격과 상한은 `devlink.outbox.*` 설정을 쓴다.
3. `TeamContextStore.prepare`가 작업·이벤트 원본, 저장된 파트 정보, 직전 요약, 구조 신호와 추출 엔티티를 묶어 추론 입력을 만든다. 해당 이벤트 ID가 그 쌍의 버전 이력에 이미 있으면 PUBLISHED로 닫는다. 이전 형식의 Outbox는 팀 단위로 순서를 지키고 처리 시 Agent의 파트를 읽는다.
4. `TeamContextInferenceClient.infer`가 다음 요약을 만든다. 추론은 쓰기 트랜잭션 밖에서 일어난다.
5. 배치는 Outbox 점유를 확인하고, 추론에 사용한 버전이 최신인 경우 `version + 1`을 삽입하면서 Outbox를 PUBLISHED로 바꾼다. 두 변경은 같은 트랜잭션에서 커밋한다. 기준 버전이 바뀌면 최신 요약으로 다시 추론한다.
6. 조회는 `TeamContextQueryService`가 저장된 최신 버전만 읽는다. 읽을 때 추론하지 않는다.

## 추론 구현

추론 클라이언트는 `com.devlink.server.client`, 프롬프트는 `com.devlink.server.prompt`, 예외(`TeamContextInferenceException`·`InvalidTeamContextEventException`)는 `com.devlink.server.exception`에 둔다. `service` 패키지에는 Outbox 적재·갱신·조회만 남는다.

| 구현 | 선택 조건 | 동작 |
| --- | --- | --- |
| `OpenAiCompatibleTeamContextInferenceClient` | `inference.base-url`·`model`이 있고, `require-api-key=true`면 `api-key`도 있을 때 | OpenAI 호환 `POST {base-url}/chat/completions`. `response_format: json_schema(strict)`로 출력 스키마를 강제하고 `max_tokens`만 보낸다. 기본값은 Upstage `https://api.upstage.ai/v1` + `solar-pro4`이며 키는 `UPSTAGE_API_KEY` |
| `StructuralTeamContextInferenceClient` | 위 조건이 아닐 때(키 없음, 테스트) | LLM 없이 미답변 질문·실패·거절만 `issues`로 올리고 답변이 달리면 닫는다. `common`·`changed`는 이전 값을 유지한다. `model` 컬럼은 `structural` |

같은 클라이언트로 OpenRouter(`openrouter-provider-guard: true`로 스키마 미지원·학습 수집 제공자 제외)나 자체 vLLM(`require-api-key: false`)으로 옮길 수 있다. 바뀌는 건 설정값뿐이다.

프롬프트는 `TeamContextPrompt.SYSTEM`에 있고 `devlink.team-context.prompt-version`을 행마다 저장한다. 프롬프트를 바꾸면 버전도 올려 전후 비교가 가능하게 한다. Upstage Solar는 `reasoning_effort`를 보내지 않으면 추론 없이 답하므로 `max_tokens`가 본문에 그대로 쓰인다.

## 데이터

`team_part_contexts` (V11): `team_id`, 정규화된 `part_a <= part_b`, `version`, JSONB `common`·`changed`·`issues`, 반영한 마지막 `last_event_id`, `model`, `prompt_version`, `created_at`. 이전 버전은 지우지 않는다.

요약 항목의 `evidence`는 `"taskId:eventId"` 좌표다. Agent가 요약을 의심하면 `get_task_result`로 원문을 읽는다. 서버 요약은 참고 자료이지 권위가 아니며 작업 상태를 바꾸지 않는다.

## API

| 항목 | 값 |
| --- | --- |
| 인증 | Agent Bearer 토큰. 경로 `teamId`와 토큰의 팀이 다르면 `403 ACCESS_DENIED`, 소유자가 팀원이 아니면 거부 |
| `part` | 필수. `FRONTEND / BACKEND / INFRA / OTHER`(대소문자 무시). 없거나 다른 값이면 `400 VALIDATION_FAILED` |
| `counterpart` | 선택. 있으면 그 쌍의 최신 한 항목(없으면 `version 0`, 빈 배열). 없으면 `part`가 참여한 모든 쌍의 최신 항목 |
| 응답 `data` | `teamId`, `part`, `contexts[]`: `counterpart`, `version`, `lastEventId`, `updatedAt`, `model`, `promptVersion`, `common[]`, `changed[]`, `issues[]` |

클라이언트 쪽은 브로커 `POST /v1/teams/context`와 MCP `get_team_context` 도구가 이 응답을 그대로 전달한다.

## 설정

```yaml
devlink:
  team-context:
    enabled: true            # 갱신기 폴링. 테스트 프로필은 false
    poll-interval-ms: 1000
    max-input-chars: 6000    # 이벤트 본문·요약 문장 길이 상한
    max-items: 20            # 배열당 항목 상한
    prompt-version: v1
    inference:
      base-url: ${TEAM_CONTEXT_INFERENCE_BASE_URL:https://api.upstage.ai/v1}
      api-key: ${UPSTAGE_API_KEY:}
      require-api-key: true  # vLLM은 false
      model: ${TEAM_CONTEXT_INFERENCE_MODEL:solar-pro4}
      timeout-ms: 60000
      max-output-tokens: 2000
      openrouter-provider-guard: false
```

운영은 `.env`(PROD_ENV)에 `UPSTAGE_API_KEY`만 넣으면 LLM 경로가 켜진다. 키가 없으면 구조 신호 전용으로 동작하고 요약 행의 `model`이 `structural`로 남는다.

## 검증

| 대상 | 테스트 |
| --- | --- |
| 엔티티 추출 | `TeamContextExtractorTest` |
| 구조 전용 추론 | `StructuralTeamContextInferenceClientTest` |
| OpenAI 호환 요청·응답·오류 | `OpenAiCompatibleTeamContextInferenceClientTest` (MockRestServiceServer) |
| 요약 정규화·JSON 왕복 | `TeamContextSummaryTest` |
| Outbox → 갱신 → 조회 전체, 중복 반영 방지, 잘못된 메시지 영구 실패, 인증·검증 오류 | `TeamContextIntegrationTest` (Testcontainers) |
| 저장 직후 점유, 파트 쌍별 재시도 순서, 35초 뒤 점유 유효성 | `TeamContextOutboxServiceIntegrationTest` (Testcontainers) |
| 추론 대기 중 기본 스케줄러 실행 | `TeamContextSchedulingTest` |

점유 시각은 PostgreSQL의 마이크로초 반올림에 맞춰 반올림한다. JVM 시계를 버림(truncate)하면 같은 시계 틱에 저장된 행의 `available_at`이 1µs 커서 점유를 놓친다. `OutboxPublicationService`는 아직 버림 방식이지만 1초 폴링이라 실질 영향은 없다.

기존 `TaskNotificationIntegrationTest`·`TaskCompleteTransactionIntegrationTest`는 outbox 행 수를 고정 검증하므로 `TeamContextOutboxWriter`를 `@MockitoBean`으로 끈다. 그 신호는 `TeamContextIntegrationTest`가 검증한다.

## 미결 사항

- 트리거 단위는 이벤트마다다. 호출량이 문제면 작업 상태 전이 단위로 묶는 디바운스를 설정값으로 추가한다.
- 요약 품질은 실제 대화 샘플로 프롬프트 버전별 비교가 필요하다. 구조 전용과 LLM 결과를 같은 이벤트 열에 돌려 비교한다.
- 웹 화면용 세션 인증 경로는 아직 없다. 필요하면 추천 API와 같은 방식으로 사용자 체인에 matcher를 추가한다.
- 3차 "LLM 호출 없음" 정책은 추천 기능 기준이었다. 이 기능은 LLM을 쓰므로 팀 합의를 기록한다.

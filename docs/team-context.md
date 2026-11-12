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
| `OpenAiCompatibleTeamContextInferenceClient` | `inference.base-url`·`model`이 있고, `require-api-key=true`면 `api-key`도 있을 때. OpenRouter 주소는 항상 키가 필요하고, 키가 주소와 짝이 맞지 않으면 제외한다 | OpenAI 호환 `POST {base-url}/chat/completions`. `response_format: json_schema(strict)`로 출력 스키마를 강제하고 `max_tokens`만 보낸다. 기본값은 OpenRouter `https://openrouter.ai/api/v1` + `upstage/solar-pro4`이며 키는 `OPENROUTER_API_KEY`(비어 있으면 이전 이름 `UPSTAGE_API_KEY`) |
| `StructuralTeamContextInferenceClient` | 위 조건이 아닐 때(키 없음, 테스트). 키가 주소와 짝이 맞지 않을 때도 여기로 온다(OpenRouter 주소에 `sk-or-`가 아닌 키, 다른 주소에 `sk-or-` 키) | LLM 없이 미답변 질문·실패·거절만 `issues`로 올리고 답변이 달리면 닫는다. `common`·`changed`는 이전 값을 유지한다. `model` 컬럼은 `structural` |

기본 대상인 OpenRouter에는 `provider: {require_parameters: true, data_collection: "deny"}`를 함께 보내 스키마 미지원·학습 수집 제공자를 제외한다. `openrouter-provider-guard`가 `auto`(기본)면 `base-url`의 호스트가 `openrouter.ai`일 때만 이 필드를 넣고, `true`면 항상, `false`면 넣지 않는다. 그래서 같은 클라이언트를 `base-url`·`model`만 바꿔 Upstage 같은 다른 제공자나 자체 vLLM(`require-api-key: false`)으로 옮길 수 있다. OpenRouter 앞에 프록시를 두면 `true`로 지정한다.

프롬프트는 `TeamContextPrompt.SYSTEM`에 있고 `devlink.team-context.prompt-version`을 행마다 저장한다. 프롬프트를 바꾸면 버전도 올려 전후 비교가 가능하게 한다. 요청에 reasoning 설정은 넣지 않는다. 기본 모델인 Upstage Solar는 `reasoning_effort`가 없으면 추론 없이 답한다. 기본으로 추론하는 모델은 추론 토큰만큼 `max_tokens`에서 빠진다.

`max-output-tokens`(기본 6000)는 가득 찬 요약을 담을 만큼 둔다. 요약은 배열당 `max-items`개, 세 배열 합쳐 60개까지 커지고, 실제 호출에서 잰 출력 토큰 수로 어림하면 60개에 약 2,800토큰과 추론 토큰이 든다. 출력이 한도에서 잘려(`finish_reason=length`) 내용이 없거나 JSON이 끊기면 클라이언트가 원인을 적은 예외를 던진다. 이 이벤트는 다른 추론 실패처럼 `devlink.outbox.max-attempts`회까지 다시 시도한 뒤 영구 실패로 남아 요약에 반영되지 않는다. 재시도마다 호출 비용이 들고, 같은 쌍의 다음 이벤트도 같은 큰 요약을 들고 가므로 같은 이유로 실패하기 쉽다. 로그에 `cut off at max_tokens`가 보이면 `TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS`로 한도를 올린다.

## 기본 모델 선택 (2026-11-12)

기본 모델은 OpenRouter의 `upstage/solar-pro4`다. 주소만 OpenRouter로 통일하고 모델은 이전 기본값(Upstage 직접 호출의 `solar-pro4`)과 같은 것을 유지했다.

`TeamContextInferenceLiveTest`의 여섯 이벤트 열(합의로 끝나는 작업 하나, 의견이 갈린 채 남는 작업 하나)을 OpenRouter 경유로 모델마다 여러 번 돌려 마지막 요약을 비교했다. 제공자 필터와 strict JSON 스키마는 네 모델 모두 받았고, 근거 좌표를 지어낸 경우는 없었다.

| 모델 | 100만 토큰당 가격(입력/출력, 달러) | 합의 사항 유지 | 미해결 쟁점 포착 | 실행별 호출 지연 중앙값의 범위 |
| --- | --- | --- | --- | --- |
| `upstage/solar-pro4` | 0.15 / 0.60 | 4/4 | 4/4 | 1.4~3.4초 |
| `openai/gpt-4o-mini` | 0.15 / 0.60 | 4/4 | 4/4 | 3.8~5.7초 |
| `openai/gpt-6.1-terra` | 1.00 / 5.00 | 3/3 | 3/3 | 5.6~6.1초 |
| `openai/gpt-6-luna` | 0.10 / 0.50 | 6/7 | 2/7 | 4.3~5.8초 |

- `openai/gpt-6-luna`는 7회 중 5회에서 의견이 갈린 작업을 요약 어디에도 남기지 않았고, 그중 1회는 요약 전체가 비었다. 추론 강도를 `minimal`로 낮춘 별도 호출에서는 이전 요약 항목도 빠뜨렸다(1회 관찰). 단가는 가장 낮지만 기본 추론 토큰이 붙어 요약 1회 비용은 `openai/gpt-4o-mini`와 비슷했다(15개 항목 요약에서 둘 다 약 0.0006달러).
- 한계: 이벤트 열 하나, 프롬프트 v1, 모델당 3~7회다. 실제 대화로 잰 품질이 아니며 순위를 확정하는 근거로는 부족하다. 지연은 한 PC에서 이 서버의 클라이언트로 잰 값이다.
- 모델을 바꾸려면 `TEAM_CONTEXT_INFERENCE_MODEL`만 지정한다. 바꾸기 전에 같은 테스트를 그 모델로 여러 번 돌려 본다.

## 데이터

`team_part_contexts`는 V15에서 만든다. `team_id`, 정규화된 `part_a <= part_b`, `version`, JSONB `common`·`changed`·`issues`, 반영한 마지막 `last_event_id`, `model`, `prompt_version`, `created_at`을 저장하고 이전 버전을 보관한다. V16은 Outbox 순번과 이벤트 중복 반영 방지 제약을 추가한다.

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
      base-url: ${TEAM_CONTEXT_INFERENCE_BASE_URL:https://openrouter.ai/api/v1}
      api-key: ${OPENROUTER_API_KEY:}
      legacy-api-key: ${UPSTAGE_API_KEY:}  # api-key가 비어 있을 때만 사용
      require-api-key: true  # vLLM은 false
      model: ${TEAM_CONTEXT_INFERENCE_MODEL:upstage/solar-pro4}
      timeout-ms: 60000
      max-output-tokens: 6000
      openrouter-provider-guard: auto  # auto(주소가 OpenRouter일 때만) / true / false
```

운영 Compose는 GitHub Actions Secret `PROD_ENV`에 등록한 추론 설정을 서버 컨테이너에 전달한다.
`OPENROUTER_API_KEY`만 추가하면 기본 대상(OpenRouter의 `upstage/solar-pro4`)으로 요약한다. 모델만 바꾸려면 `TEAM_CONTEXT_INFERENCE_MODEL`을,
제공자를 바꾸려면 `TEAM_CONTEXT_INFERENCE_BASE_URL`과 모델, 그 제공자의 키를 함께 지정한다([`deploy/.prod.env.example`](../deploy/.prod.env.example)).
키가 없으면 구조 신호 전용으로 동작하고 요약 행의 `model`이 `structural`로 남는다.

기본 주소는 2026-11-12에 Upstage에서 OpenRouter로 바뀌었다(S15P31D108-245). 그 전부터 운영하던 배포는 다음을 확인한다.

- `PROD_ENV`에 주소·모델을 이미 지정한 배포(예: OpenRouter + `openai/gpt-4o-mini`)는 그 값이 그대로 쓰인다. 새 기본 모델을 쓰려면 `TEAM_CONTEXT_INFERENCE_MODEL`을 지우거나 바꾼다.
- `UPSTAGE_API_KEY`에 Upstage 키만 두고 주소를 지정하지 않은 배포는 기본 주소가 OpenRouter가 된다. 서버는 OpenRouter 키 형식(`sk-or-`)이 아닌 키를 OpenRouter로 보내지 않고 구조 신호 전용으로 동작하며, 시작할 때 경고 로그를 남긴다. 요약 행의 `model`이 `structural`로 남는다. LLM 요약을 계속 쓰려면 OpenRouter 키로 바꾸거나 `TEAM_CONTEXT_INFERENCE_BASE_URL=https://api.upstage.ai/v1`과 `TEAM_CONTEXT_INFERENCE_MODEL=solar-pro4`를 지정한다.
- 제공자를 OpenRouter에서 다른 곳으로 바꿀 때는 `OPENROUTER_API_KEY`를 지우거나 그 제공자의 키로 바꾼다. `OPENROUTER_API_KEY`가 `UPSTAGE_API_KEY`보다 먼저 쓰이기 때문이다. OpenRouter 키가 남아 있으면 서버는 그 키를 다른 주소로 보내지 않고 구조 신호 전용으로 동작한다. 다만 제공자 필터를 `true`로 명시하면 OpenRouter 앞의 프록시로 보고 이 확인을 건너뛴다. 이전 템플릿은 `TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=true`를 적도록 했으므로, OpenRouter를 떠날 때는 이 줄도 지운다. 남겨 두면 OpenRouter 키와 `provider` 필드가 새 주소로 전송된다.
- OpenRouter 주소는 `require-api-key`와 무관하게 키가 있어야 켜진다.
- 주소만 다른 제공자로 지정하고 모델을 지정하지 않은 배포는 모델이 OpenRouter 기본값(`upstage/solar-pro4`)이 되어 호출이 실패한다. 시작할 때 경고 로그를 남긴다. 모델을 함께 지정한다.
- `TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD`를 지정하지 않으면 `auto`다. 주소가 `openrouter.ai`가 아니면 제공자 필터를 보내지 않으므로, OpenRouter 앞에 프록시나 게이트웨이를 두었다면 `true`로 지정한다.

실제 엔드포인트 확인은 `TEAM_CONTEXT_LIVE=true`와 키를 환경 변수로 두고 `./gradlew test --tests '*TeamContextInferenceLiveTest' -i`로 한다. 여섯 이벤트를 차례로 넣어 스키마와 근거 좌표를 검사하고 요약을 출력한다. 주소·모델·제공자 필터 변수는 서버와 같은 규칙으로 읽는다.

## 검증

| 대상 | 테스트 |
| --- | --- |
| 엔티티 추출 | `TeamContextExtractorTest` |
| 구조 전용 추론 | `StructuralTeamContextInferenceClientTest` |
| OpenAI 호환 요청·응답·오류, 제공자 필터의 적용 범위 | `OpenAiCompatibleTeamContextInferenceClientTest` (MockRestServiceServer) |
| 설정 기본값과 환경 변수 연결(새 키 이름, 이전 키 이름, 주소를 지정한 구성, 키 불일치 경고) | `TeamContextInferenceWiringTest` |
| 실제 엔드포인트에서의 증분 요약(선택 실행) | `TeamContextInferenceLiveTest` |
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

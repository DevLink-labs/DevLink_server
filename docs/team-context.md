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
| `OpenAiCompatibleTeamContextInferenceClient` | `inference.base-url`·`model`이 있고, `require-api-key=true`면 `api-key`도 있을 때. OpenRouter 주소는 항상 키가 필요하고, 키가 주소와 짝이 맞지 않으면 제외한다 | OpenAI 호환 `POST {base-url}/chat/completions`. `response_format: json_schema(strict)`로 출력 스키마를 강제하고 `max_tokens`만 보낸다. 기본값은 OpenRouter `https://openrouter.ai/api/v1` + `openai/gpt-6-luna`이며 키는 `OPENROUTER_API_KEY` |
| `StructuralTeamContextInferenceClient` | 위 조건이 아닐 때(키 없음, 테스트). 키가 주소와 짝이 맞지 않을 때도 여기로 온다(OpenRouter 주소에 `sk-or-`가 아닌 키, 다른 주소에 `sk-or-` 키) | LLM 없이 미답변 질문·실패·거절만 `issues`로 올리고 답변이 달리면 닫는다. `common`·`changed`는 이전 값을 유지한다. `model` 컬럼은 `structural` |

기본 대상인 OpenRouter에는 `provider: {require_parameters: true, data_collection: "deny"}`를 함께 보내 스키마 미지원·학습 수집 제공자를 제외한다. `openrouter-provider-guard`가 `auto`(기본)면 `base-url`의 호스트가 `openrouter.ai`일 때만 이 필드를 넣고, `true`면 항상, `false`면 넣지 않는다. 그래서 같은 클라이언트를 `base-url`·`model`만 바꿔 자체 호스팅 서버(vLLM 등, `require-api-key: false`)로 옮길 수 있다. OpenRouter 앞에 프록시를 두면 `true`로 지정한다.

프롬프트는 `TeamContextPrompt.SYSTEM`에 있고 `devlink.team-context.prompt-version`을 행마다 저장한다. 프롬프트를 바꾸면 버전도 올려 전후 비교가 가능하게 한다. 요청에 reasoning 설정은 넣지 않는다. 기본 모델(`openai/gpt-6-luna`)은 기본으로 추론 토큰을 조금 쓰고 그만큼 `max_tokens`에서 빠진다(15개 항목 요약에서 약 170토큰).

`max-output-tokens`(기본 6000)는 가득 찬 요약을 담을 만큼 둔다. 요약은 배열당 `max-items`개, 세 배열 합쳐 60개까지 커지고, 실제 호출에서 잰 출력 토큰 수로 어림하면 60개에 약 2,800토큰과 추론 토큰이 든다. 출력이 한도에서 잘려(`finish_reason=length`) 내용이 없거나 JSON이 끊기면 클라이언트가 원인을 적은 예외를 던진다. 이 이벤트는 다른 추론 실패처럼 `devlink.outbox.max-attempts`회까지 다시 시도한 뒤 영구 실패로 남아 요약에 반영되지 않는다. 재시도마다 호출 비용이 들고, 같은 쌍의 다음 이벤트도 같은 큰 요약을 들고 가므로 같은 이유로 실패하기 쉽다. 로그에 `cut off at max_tokens`가 보이면 `TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS`로 한도를 올린다.

## 기본 모델 선택 (2026-11-12)

기본 모델은 OpenRouter의 `openai/gpt-6-luna`다. 아래 비교를 본 뒤 프로젝트 담당자가 정했다. 비교한 모델 가운데 단가가 가장 낮다.

`TeamContextInferenceLiveTest`의 여섯 이벤트 열(합의로 끝나는 작업 하나, 의견이 갈린 채 남는 작업 하나)을 OpenRouter 경유로 모델마다 여러 번 돌려 마지막 요약을 비교했다. 제공자 필터와 strict JSON 스키마는 네 모델 모두 받았고, 근거 좌표를 지어낸 경우는 없었다.

| 모델 | 100만 토큰당 가격(입력/출력, 달러) | 합의 사항 유지 | 미해결 쟁점 포착 | 실행별 호출 지연 중앙값의 범위 |
| --- | --- | --- | --- | --- |
| `upstage/solar-pro4` | 0.15 / 0.60 | 4/4 | 4/4 | 1.4~3.4초 |
| `openai/gpt-4o-mini` | 0.15 / 0.60 | 4/4 | 4/4 | 3.8~5.7초 |
| `openai/gpt-6.1-terra` | 1.00 / 5.00 | 3/3 | 3/3 | 5.6~6.1초 |
| `openai/gpt-6-luna` | 0.10 / 0.50 | 8/9 | 3/9 | 4.3~6.5초 |

- 기본 모델의 알려진 약점: `openai/gpt-6-luna`는 9회 중 6회에서 의견이 갈린 작업을 요약 어디에도 남기지 않았고, 그중 1회는 요약 전체가 비었다. 9회 가운데 2회는 기본 모델로 정한 뒤 최종 코드로 돌린 것이다(합의 유지 2/2, 쟁점 포착 1/2). 추론 강도를 `minimal`로 낮춘 별도 호출에서는 이전 요약 항목도 빠뜨렸다(1회 관찰). 운영에서 `issues`가 비는 일이 잦으면 `TEAM_CONTEXT_INFERENCE_MODEL`을 `upstage/solar-pro4`나 `openai/gpt-4o-mini`로 지정해 같은 테스트로 비교한다.
- 비용: `openai/gpt-6-luna`는 단가가 가장 낮지만 기본 추론 토큰이 붙어 요약 1회 비용은 `openai/gpt-4o-mini`와 비슷했다(15개 항목 요약에서 둘 다 약 0.0006달러).
- 한계: 이벤트 열 하나, 프롬프트 v1, 모델당 3~9회다. 실제 대화로 잰 품질이 아니며 순위를 확정하는 근거로는 부족하다. 지연은 한 PC에서 이 서버의 클라이언트로 잰 값이다.
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
      require-api-key: true  # vLLM은 false
      model: ${TEAM_CONTEXT_INFERENCE_MODEL:openai/gpt-6-luna}
      timeout-ms: ${TEAM_CONTEXT_INFERENCE_TIMEOUT_MS:60000}
      max-output-tokens: 6000
      openrouter-provider-guard: auto  # auto(주소가 OpenRouter일 때만) / true / false
```

운영 Compose는 GitHub Actions Secret `PROD_ENV`에 등록한 추론 설정을 서버 컨테이너에 전달한다.
`OPENROUTER_API_KEY`만 추가하면 기본 대상(OpenRouter의 `openai/gpt-6-luna`)으로 요약한다. 모델만 바꾸려면 `TEAM_CONTEXT_INFERENCE_MODEL`을,
자체 호스팅 서버를 쓰려면 `TEAM_CONTEXT_INFERENCE_BASE_URL`과 모델을 함께 지정한다([`deploy/.prod.env.example`](../deploy/.prod.env.example)).
호출 대상은 OpenRouter와 자체 호스팅 서버 두 가지다. Upstage 직접 호출용 설정은 없앴다. 자체 호스팅은 아래 "자체 호스팅 (vLLM)"을 따른다.
키가 없으면 구조 신호 전용으로 동작하고 요약 행의 `model`이 `structural`로 남는다.

기본 주소는 2026-11-12에 Upstage에서 OpenRouter로 바뀌었고, 같은 변경에서 이전 키 이름(`UPSTAGE_API_KEY`)을 없앴다(S15P31D108-245). 그 전부터 운영하던 배포는 다음을 확인한다.

- `UPSTAGE_API_KEY`는 더 이상 읽지 않고 Compose도 서버에 전달하지 않는다. 이전 템플릿은 OpenRouter 키를 이 이름으로 두게 했으므로, 그 배포는 `PROD_ENV`에서 같은 값을 `OPENROUTER_API_KEY`로 옮긴다. 옮기지 않으면 키가 없는 상태가 되어 구조 신호 전용으로 동작하고 요약 행의 `model`이 `structural`로 남는다. 이때 배포와 CI는 그대로 성공하고, 드러나는 것은 시작 로그의 INFO 한 줄(`OPENROUTER_API_KEY is empty`)뿐이다. 순서: 이 변경을 배포하기 전에 `PROD_ENV`에 `OPENROUTER_API_KEY=<같은 값>`을 먼저 추가하고, 배포 뒤에 `UPSTAGE_API_KEY`를 지운다. 이전 버전의 Compose는 `OPENROUTER_API_KEY`를 서버에 전달하지 않으므로 먼저 추가해도 동작이 바뀌지 않는다.
- Upstage 키로 Upstage를 직접 부르던 배포는 OpenRouter 키를 발급해 `OPENROUTER_API_KEY`에 넣는다. 같은 모델을 쓰려면 `TEAM_CONTEXT_INFERENCE_MODEL=upstage/solar-pro4`를 지정한다. Upstage 키를 `OPENROUTER_API_KEY`에 넣으면 서버는 OpenRouter 키 형식(`sk-or-`)이 아닌 키를 OpenRouter로 보내지 않고 구조 신호 전용으로 동작하며, 시작할 때 경고 로그를 남긴다.
- `PROD_ENV`에 주소·모델을 이미 지정한 배포(예: OpenRouter + `openai/gpt-4o-mini`)는 키를 `OPENROUTER_API_KEY`로 옮긴 뒤 그 주소·모델이 그대로 쓰인다. 새 기본 모델을 쓰려면 `TEAM_CONTEXT_INFERENCE_MODEL`을 지우거나 바꾼다.
- 자체 호스팅 주소로 바꿀 때는 `OPENROUTER_API_KEY`를 지운다. OpenRouter 키가 남아 있으면 서버는 그 키를 다른 주소로 보내지 않고 구조 신호 전용으로 동작한다. 다만 제공자 필터를 `true`로 명시하면 OpenRouter 앞의 프록시로 보고 이 확인을 건너뛴다. 이전 템플릿은 `TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=true`를 적도록 했으므로, OpenRouter를 떠날 때는 이 줄도 지운다. 남겨 두면 OpenRouter 키와 `provider` 필드가 새 주소로 전송된다.
- OpenRouter 주소는 `require-api-key`와 무관하게 키가 있어야 켜진다.
- 주소만 자체 호스팅 서버로 지정하고 모델을 지정하지 않은 배포는 모델이 OpenRouter 기본값(`openai/gpt-6-luna`)이 되어 호출이 실패한다. 시작할 때 경고 로그를 남긴다. 모델을 함께 지정한다.
- `TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD`를 지정하지 않으면 `auto`다. 주소가 `openrouter.ai`가 아니면 제공자 필터를 보내지 않으므로, OpenRouter 앞에 프록시나 게이트웨이를 두었다면 `true`로 지정한다.

실제 엔드포인트 확인은 `TEAM_CONTEXT_LIVE=true`와 키를 환경 변수로 두고 `./gradlew test --tests '*TeamContextInferenceLiveTest' -i`로 한다. 여섯 이벤트를 차례로 넣어 스키마와 근거 좌표를 검사하고 요약을 출력한다. 주소·모델·제공자 필터 변수는 서버와 같은 규칙으로 읽는다.

## 자체 호스팅 (vLLM)

외부 API 없이 팀·파트 Context 요약을 돌리는 구성이다(S15P31D108-246). 요약 요청은 [vLLM](https://docs.vllm.ai)의 OpenAI 호환 서버가 받는다.
담당 Agent 추천의 결정 모델(별도 PR DevLink_server#98, 이 브랜치에는 없다)은 이 구성과 무관하다. 로컬 결정 모델은 붙이지 않기로 했고(담당자 결정, 2026-11-12), 그 기능은 꺼 두거나 OpenRouter API로 켠다. 결정 모델은 자체 변수(`DECISION_MODEL_API_KEY`)에서 키를 읽으므로, 이 구성이 `OPENROUTER_API_KEY`를 비워도 영향받지 않는다.

실행:

```bash
VLLM_MODEL=<Hugging Face 모델 ID> docker compose -f docker-compose.yml -f docker-compose.vllm.yml up -d --build
```

[`docker-compose.vllm.yml`](../docker-compose.vllm.yml)은 기본 구성 위에 겹쳐 쓰는 파일이다. `vllm` 서비스를 추가하고 `app`의 추론 설정을 그 서비스로 돌린다. 이 파일 없이 실행하는 기본 구성은 바뀌지 않는다.

| 변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `VLLM_MODEL` | 없음(필수) | vLLM이 올릴 Hugging Face 모델 ID. 서버가 요청에 넣는 모델 이름도 이 값이다 |
| `VLLM_MAX_MODEL_LEN` | 32768 | vLLM의 `--max-model-len`. 모델이 지원하는 길이를 넘길 수 없다 |
| `VLLM_IMAGE_TAG` | latest | `vllm/vllm-openai` 이미지 태그. 운영에서는 확인한 버전으로 고정한다 |
| `HF_TOKEN` | 없음 | 접근 승인이 필요한 모델을 받을 때만 |
| `VLLM_START_PERIOD` | 10m | 헬스체크 시작 유예. 모델 내려받기가 오래 걸리면 늘린다 |
| `TEAM_CONTEXT_INFERENCE_TIMEOUT_MS` | 120000(이 구성), 60000(기본 구성) | 요약 한 번의 제한 시간 |

조건:

- Linux, NVIDIA GPU, NVIDIA Container Toolkit. GPU 메모리는 고른 모델과 `VLLM_MAX_MODEL_LEN`에 따라 달라진다.
- 모델은 채팅 템플릿이 있는 텍스트 생성 모델이어야 하고, 한국어 요약과 JSON 스키마에 맞춘 출력을 할 수 있어야 한다. 기본 모델은 정하지 않았다. 고른 모델은 아래 확인 방법으로 여러 번 돌려 본다.
- 서버가 보내는 요청은 `POST /v1/chat/completions`이고 `response_format: json_schema`(strict)와 `max_tokens`를 담는다. `Authorization` 헤더와 OpenRouter 전용 `provider` 필드는 보내지 않는다.
- vLLM 포트는 호스트에 열지 않는다. 키 없이 받으므로 compose 네트워크 안의 `app`만 접근한다. 다른 장비의 vLLM을 쓰려면 이 파일 대신 `TEAM_CONTEXT_INFERENCE_BASE_URL`·`TEAM_CONTEXT_INFERENCE_MODEL`·`TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY=false`를 직접 지정하고, 그 구간의 접근 제한은 운영자가 맡는다.

문맥 길이와 출력 한도:

- 한 요청의 입력 토큰과 `max_tokens`의 합이 `--max-model-len`을 넘으면 안 된다. `max_tokens`는 `TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS`(기본 6000)다.
- 입력은 시스템 프롬프트, 이전 요약, 새 이벤트 본문, 구조 신호다. 코드가 정한 상한은 다음과 같다. 이벤트 본문은 `max-input-chars`(6000자)까지인데, 완료된 작업의 상태 변경은 내용과 결과를 이어 붙여 약 12,000자까지 간다. 이전 요약은 세 배열 합쳐 60개 항목까지이고 항목의 각 문장도 6000자까지 허용되므로, 실제 크기는 이전 출력(`max_tokens` 이하)이 정한다. 구조 신호의 미답변 질문은 질문당 300자이고 개수 제한이 없다.
- 한국어는 토크나이저에 따라 글자당 1~2토큰이 들 수 있다. 이 가정으로 어림하면 12,000자 이벤트가 1만 2천~2만 4천 토큰이고, 이전 요약이 최대 약 6천 토큰이라 입력이 1만 8천~3만 토큰이 된다(재지 않았다). 여기에 출력 6000을 더하면 높은 쪽에서는 32768을 넘는다. 그런 요청은 거절되고 그 이벤트는 요약에서 빠진다. 보통 크기의 이벤트에는 32768이 맞지만 여유 있는 값은 아니며, 문맥이 8k·16k인 모델은 기본 `max_tokens`로는 맞지 않는다.
- 문맥이 더 짧은 모델을 쓰면 `VLLM_MAX_MODEL_LEN`을 그 길이로 낮추고 `TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS`도 함께 낮춘다. 출력 한도가 요약보다 작으면 `cut off at max_tokens` 오류가 난다(위 "추론 구현" 참조). 입력이 문맥을 넘는 요청은 vLLM이 거절하고, 그 이벤트는 다른 추론 실패처럼 재시도 뒤 요약에서 빠진다.

시작 순서와 장애:

- `app`은 `vllm`이 준비될 때까지 기다린다. `vllm`이 헬스체크 시간(시작 유예 `VLLM_START_PERIOD` 기본 10분 + 15초 간격 20회, 기본값으로 약 15분) 안에 준비되지 못하거나 시작 중에 종료되면 `docker compose up`이 실패하고 `app`이 시작되지 않는다. 이때는 요약만이 아니라 작업·질문·답변 API 전체가 내려가 있다. 원인은 모델 내려받기가 오래 걸리는 경우, `VLLM_MAX_MODEL_LEN`이 모델이 지원하는 길이보다 큰 경우, GPU 메모리 부족 등이다. `vllm`에는 재시작 정책을 두지 않았다. 시작 중에 죽은 컨테이너를 계속 다시 띄우면 `up`이 원인 표시 없이 끝없이 기다리기 때문이다. 실행 중에 `vllm`이 죽으면 운영자가 다시 띄운다. `docker compose logs vllm`으로 확인하고, 내려받기가 오래 걸리면 `VLLM_START_PERIOD`를 늘리거나 `vllm`만 먼저 띄워(`up -d vllm`) 모델을 받아 둔다. `vllm`이 준비된 뒤 같은 `up` 명령을 다시 실행한다.
- 실행 중에 추론이 실패한 이벤트는 `devlink.outbox.max-attempts`회(기본 5회, 재시도 간격 1·2·4·8초) 뒤 영구 실패로 남아 요약에 반영되지 않는다. vLLM이 꺼져 있어 바로 실패하면 약 15초, 응답 없이 멈춰 있으면 시도마다 제한 시간까지 더 기다린 뒤다. vLLM이 그보다 오래 멈춰 있으면 그동안의 이벤트가 요약에서 빠진다. 이미 떠 있는 `app`의 작업·질문·답변은 영향받지 않는다.
- 요약보다 서버 가동을 우선하려면 `docker-compose.vllm.yml`을 고쳐 `app`의 `depends_on`에서 `vllm`을 뺀다(Compose가 두 파일의 `depends_on`을 합치므로 명령줄로는 뺄 수 없다). 그러면 vLLM이 준비되기 전의 이벤트가 위와 같이 빠진다.
- 서버 시작 로그의 `Team context inference uses vllm with model …` 줄로 이 구성이 적용됐는지 확인한다. 이 줄이 없으면 구조 신호 전용으로 동작하고 있다.

확인 방법(자체 호스팅 주소가 닿는 곳에서):

```bash
TEAM_CONTEXT_LIVE=true TEAM_CONTEXT_INFERENCE_BASE_URL=http://<vLLM 주소>:8000/v1 TEAM_CONTEXT_INFERENCE_MODEL=<모델 ID> \
TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY=false OPENROUTER_API_KEY= ./gradlew test --tests '*TeamContextInferenceLiveTest' -i
```

여섯 이벤트를 차례로 넣어 스키마와 근거 좌표를 검사하고, 합의 사항 유지와 미해결 쟁점 포착 여부, 호출 지연을 출력한다. 셸에 OpenRouter 키가 남아 있으면 서버 규칙대로 그 키를 다른 주소로 보내지 않아 테스트가 실패하므로 명령에서 비운다.

확인하지 못한 것(2026-11-12):

- 실제 vLLM 서버로는 실행하지 않았다. 이 구성 파일은 Compose 문법 검사와, 같은 요청을 받는 가짜 서버로만 확인했다.
- vLLM이 `json_schema`의 `strict` 키를 받는지, 문맥 길이를 넘는 요청에 어떤 응답을 주는지, 헬스체크 명령이 이미지 안에서 동작하는지는 실제 실행으로 확인해야 한다.
- "시작 순서와 장애"에 적은 Compose 동작(`vllm`이 준비되지 못하면 `up`이 실패하고 `app`이 시작되지 않는다)은 Compose의 일반 동작에서 따온 것이고 이 구성으로 실행해 본 것이 아니다.
- 어떤 모델이 이 요약에 맞는지는 재지 않았다.

## 검증

| 대상 | 테스트 |
| --- | --- |
| 엔티티 추출 | `TeamContextExtractorTest` |
| 구조 전용 추론 | `StructuralTeamContextInferenceClientTest` |
| OpenAI 호환 요청·응답·오류, 제공자 필터의 적용 범위 | `OpenAiCompatibleTeamContextInferenceClientTest` (MockRestServiceServer) |
| 설정 기본값과 환경 변수 연결(키 이름, 읽지 않는 이전 키 이름, 주소를 지정한 구성, 키 불일치 경고, vLLM 구성이 넘기는 값) | `TeamContextInferenceWiringTest` |
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

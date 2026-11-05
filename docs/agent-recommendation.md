# 담당 Agent 추천 구현 현황

`POST /api/v1/teams/{teamId}/agents/recommendations`. Agent 연결 토큰으로 인증하는 읽기 전용 계산이며, 설계 문서(3차 MVP 담당 Agent 자동 탐색 설계) 5·6절의 산식과 응답을 따른다.

## 실행 흐름

1. AgentRecommendationController가 Agent principal을 확인한다. 요청 DTO는 StrictStringDeserializer로 JSON 문자열만 받아 숫자·불리언 입력은 400 VALIDATION_FAILED가 된다. 질문 길이는 코드포인트 기준이라 Bean Validation 대신 서비스에서 검증한다.
2. AgentRecommendationService.recommend가 질문(양끝 공백 제거 후 1~1,000 코드포인트)과 선택 repoKey(소문자 16진수 64자리)를 검증하고, 경로 teamId와 principal 팀이 다르면 ACCESS_DENIED, 현재 멤버십이 없으면 TeamAuthorizationService가 거부한다.
3. 평가 시각 T를 요청당 한 번 정한다. 소유자가 현재 팀 멤버인 Agent만 조회(AgentRepository.findRecommendationCandidates)해 탈퇴자의 Agent를 점수 계산 전에 제외하고, 요청 Agent 자신을 뺀 후보와 소유자 집합을 만든다.
4. RecommendationGitEvidenceService.collect가 팀 전체 저장소에서 질문과 토큰을 공유하는 관련 파일을 찾고, 후보 소유자별 Git 근거(작업 중·고유 커밋 수·최신 커밋·근거 파일)를 집계한다.
5. 후보마다 RecommendationRoleMatcher로 역할 태그를 맞추고 RecommendationScorePolicy로 점수를 계산한다. 같은 소유자의 Agent는 같은 Git 근거를 받는다.
6. 양의 최고점과 5점 이내인 양의 후보를 점수 내림차순·Agent ID 오름차순으로 최대 5명 반환한다. 양의 후보가 없으면 INSUFFICIENT_EVIDENCE다.

## 단계별 책임

| 클래스 | 책임 | 비고 |
| --- | --- | --- |
| RecommendationTokenizer | NFKC, camelCase·약어·영문/한글 경계 분리, 소문자화, 문자·숫자 이외 구분 | 경로 토큰은 확장자를 빼고 한 글자·숫자만인 토큰 제외 |
| RecommendationRoleMatcher | 태그 토큰 연속열 일치, 마지막 한글 토큰의 조사 허용, roleDescription fallback 최대 10개 | 영문 태그 뒤 조사는 토큰화에서 이미 분리됨 |
| RecommendationGitEvidenceService | 관련 파일 집합 → 사용자별 근거 집계 | 4차 파일 의존성은 관련 파일 집합을 넓히는 자리에 들어간다 |
| RecommendationScorePolicy | 역할 20×min(R,3), 작업 중 15, 횟수 3×min(C,5), 최근성 10×2^(-d/14), 표시 반올림 | 비교는 반올림 전 값 |
| AgentRecommendationService | 검증·권한·후보 선택·응답 조립 | DataAccessException은 503 GIT_ROUTING_UNAVAILABLE |

## 근거 집계 규칙

- 커밋 기간은 `T-90일 ≤ committed_at ≤ T`이고 `author_user_id`가 null인 커밋은 경로 탐색과 점수 모두에서 제외한다.
- 고유 커밋은 `(repo_id, commit_sha)` 기준이다. 한 커밋이 관련 파일 여러 개를 바꿔도 한 번이다.
- 작업 관측은 `T < expires_at`인 행만 점수에 쓴다. 후보 사용자 집합에 유효 관측이 있으면 `workingState=FRESH`, 만료 관측만 있으면 `STALE`, 행이 없으면 `NOT_COLLECTED`.
- `gitCoverage.state`는 팀 저장소가 없으면 `NOT_COLLECTED/NO_TEAM_REPOSITORIES`, 저장소는 있으나 커밋·관측 행이 없으면 `NOT_COLLECTED/NO_RETAINED_OBSERVATIONS`, 그 외 `AVAILABLE`.
- 관련 파일은 후보별로 작업 중 우선, 근거 시각 내림차순, 저장소 ID·경로 오름차순 최대 5개다. `sources`는 `WORKING`, `COMMIT`이며 4차에 값이 추가될 수 있다.

## 접속 상태 연계 (담당: 최보경, TODO)

현재 `candidates[].connectionStatus`는 AgentRecommendationService.toResponse에서 `Agent.getStatus()`를 그대로 넣는다. 이 값은 `agents.status` 컬럼이며 AgentPresenceService가 브로커 연결·하트비트에 따라 갱신한다. 설계 문서 5.2는 접속 상태를 추천 시점 값으로 정의하고 점수에는 넣지 않는다.

남은 작업은 다음과 같다.

- 추천 시점의 실제 연결 여부를 ConnectionRegistry 또는 AgentPresenceService에서 읽어 `connectionStatus`를 채운다. 하트비트 만료 후 아직 OFFLINE으로 갱신되지 않은 Agent가 ONLINE으로 보이는 창을 없앤다.
- OFFLINE 후보도 점수 기준에 들면 반환하므로, 호출자가 그 후보를 골라 `create_task`를 호출했을 때 기존 `RECEIVER_OFFLINE` 처리로 거부되고 다음 후보를 다시 고를 수 있는 전달 경로를 통합 테스트로 검증한다.
- 상태 조회가 추가 쿼리나 레지스트리 조회를 수반하면 후보 최대 5명 기준으로 비용을 확인한다.

연계 코드의 진입점은 AgentRecommendationService.toResponse 하나이며, 점수 계산과 후보 선택은 손대지 않는다.

## 한계

- 한영 번역·동의어 매칭은 없다. 한글만 있는 질문은 역할 태그로만 점수를 얻는다.
- 관련 파일 탐색은 팀의 90일치 고유 경로를 모두 읽어 메모리에서 토큰을 비교한다. 저장소가 매우 크면 경로 토큰 인덱스가 필요할 수 있다.
- `file_dependencies`는 읽지 않는다. 4차에서 RecommendationGitEvidenceService.findRelatedPaths 뒤에 역방향 조회를 추가한다.

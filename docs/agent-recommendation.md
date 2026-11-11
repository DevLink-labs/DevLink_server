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

## 접속 상태 연계 (담당: 최보경)

`candidates[].connectionStatus`는 AgentRecommendationService.toResponse에서 AgentPresenceService.connectionStatus로 조회한다. `agents.status`가 ONLINE이고, 현재 브로커 소켓이 열려 있으며, 하트비트가 유효할 때만 ONLINE을 반환한다. 나머지는 OFFLINE이다. 접속 상태는 점수나 후보 선정에 반영하지 않는다.

조회 기준과 비용은 다음과 같다.

- ConnectionRegistry.hasActiveConnection은 단조 시계로 마지막 PING 이후 경과 시간을 확인한다. 정리 작업과 동일한 `devlink.broker.heartbeat-timeout-ms` 설정(기본 40초)을 사용하고, 경과 시간이 제한과 같아지는 순간부터 만료로 본다. 정리 작업이나 DB 갱신이 늦어져도 추천 응답은 OFFLINE이다.
- 상태 조회는 연결 제거·소켓 종료·DB 갱신·이벤트 발행을 하지 않는다. 기존 하트비트 정리와 재접속 처리는 그대로 유지된다.
- 점수로 최종 선정된 최대 5명(근거 부족 참고 후보 포함)만 조회한다. 이미 읽은 Agent와 메모리 레지스트리를 사용하므로 추가 DB 쿼리는 없으며, 레지스트리 조회도 최대 5회다. DB 상태가 OFFLINE이면 레지스트리 조회를 생략한다.
- 응답은 조회 당시의 상태다. 추천 후 실제 작업 요청 사이에 연결 상태가 바뀔 수 있으므로 전달 결과는 작업 API에서 별도로 확인한다.

OFFLINE 후보를 선택한 후의 작업 요청은 생성과 전달 결과를 구분한다.

- DB 상태도 OFFLINE이면 작업을 PENDING으로 생성하고 201 응답의 `delivery.status=DELIVERY_NOT_SENT`, `delivery.reasonCode=RECEIVER_OFFLINE`으로 반환한다.
- DB는 ONLINE이지만 실제 연결이 없으면 추천 상태는 OFFLINE이며, 작업 요청의 전달 사유는 기존 `CONNECTION_UNAVAILABLE`이다.
- 수신자가 다시 ONLINE을 보고하면 기존 미전달 작업을 재전송한다. 생성 실패로 간주해 다른 후보에게 자동으로 새 작업을 만들지 않는다.

검증은 ConnectionRegistryTest의 만료 경계·PING 갱신·재접속, AgentPresenceServiceTest의 상태 조합·추가 DB 접근 없음, AgentRecommendationServiceTest의 점수·후보 유지·최대 5명 조회, AgentRecommendationIntegrationTest의 실제 소켓 접속·단절·재접속 및 추천 후보에 대한 작업 생성·전달 결과로 수행한다.

## 한계

- 한영 번역·동의어 매칭은 없다. 한글만 있는 질문은 역할 태그로만 점수를 얻는다.
- 관련 파일 탐색은 팀의 90일치 고유 경로를 모두 읽어 메모리에서 토큰을 비교한다. 저장소가 매우 크면 경로 토큰 인덱스가 필요할 수 있다.
- 커밋 근거와 import 관계를 조회할 때는 경로를 정렬하고 최대 1,000개씩 나누어 조회한다.
- 직접 관련 파일의 검색 대상은 최근 90일 커밋 경로와 유효한 작업 관측 경로다. 그래프에만 존재하는 파일은 직접 매칭 대상에 포함하지 않는다.

## 4차: import 역방향 확장

- 직접 관련 파일을 `target_relative_path`로 사용하는 `IMPORT` 관계의 `source_relative_path`를 간접 관련 파일로 추가한다. 원래 직접 관련 파일에서 한 단계만 탐색한다.
- 같은 팀·저장소의 현재 게시된 `analysis_commit_sha`와 일치하는 관계만 읽는다. 그래프가 없으면 기존 직접 관련 파일로 계산한다. 분석 브랜치는 저장소에 등록된 브랜치 기준이다.
- 직접 파일마다 같은 저장소의 현재 게시된 스냅샷에서 고유 importer 수를 센다. 20개를 초과하는 파일은 허브로 보고 확장 대상에서 제외한다. 허브 파일 자체의 직접 매칭 점수는 유지한다.
- importer가 20개 이하인 직접 파일을 하나라도 참조하면 간접 관련 파일로 추가한다. 조건을 만족하는 직접 파일이 없으면 그 importer를 추가하지 않는다.
- 직접 관련 가중치는 1.0, 간접 관련 가중치는 0.5다. 직접 매칭이 우선하며 동일 파일은 한 번만 집계한다.
- W = 유효한 현재 작업 파일의 최대 가중치 × 15.
- F = 3 × min(직접 커밋 수 + min(간접 커밋 가중 합, 2.0), 5). 간접 커밋만으로 얻는 F는 최대 6점이다. 커밋은 `(repo_id, commit_sha)`로 구분하며 직접·간접 파일을 함께 수정한 커밋은 직접 커밋으로 한 번 집계한다.
- D = 관련 커밋별 `가중치 × 10 × 2^(-경과일/14)` 중 최댓값. 응답의 `latestCommitAt`은 가중치와 무관한 실제 최신 관련 커밋 시각이다.
- 역할 60점 + Git 40점과 후보 선택 정책은 유지한다.
- `relatedFiles`의 각 항목은 `repoId`, `relativePath`, `sources`, `observedAt`으로 응답한다. 파일 가중치는 서버 내부 점수 계산에 사용한다.
- 근거 파일 정렬은 작업 중 우선, 직접 관련 우선, 근거 시각 내림차순, 저장소 ID·경로 순이며 최대 5개를 반환한다. 점수는 표시 개수와 관계없이 전체 근거로 계산한다.

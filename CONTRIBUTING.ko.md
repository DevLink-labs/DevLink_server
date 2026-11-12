# 기여 가이드

[English](CONTRIBUTING.md) · **한국어**

[프로젝트 소개](README.ko.md) · [커뮤니티 행동 규칙](CODE_OF_CONDUCT.ko.md) · [보안 정책](SECURITY.ko.md)

DevLink Server의 버그 제보, 문서 개선과 기능 제안을 환영합니다. 이 문서는 서버 저장소의 개발 환경과 기존 브랜치·커밋·PR 규칙을 정리한 기본 가이드이며, 상세 기여 절차는 추후 보완합니다.

## 시작하기

- [커뮤니티 행동 규칙](CODE_OF_CONDUCT.ko.md)을 확인해 주세요.
- 일반 오류와 기능 제안은 [Issues](https://github.com/SSAFY-URAYAHA/DevLink_server/issues)에 남겨주세요. 오류 제보에는 사용 환경, 재현 방법, 기대한 동작과 실제 동작을 포함합니다.
- 보안 취약점은 공개 Issue 대신 [보안 정책](SECURITY.ko.md)의 제보 절차를 따릅니다.
- PR에는 변경 목적과 범위, 관련 Issue, 실행한 검사·테스트와 결과를 기록합니다. 비밀번호·토큰·개인 설정 파일은 포함하지 않습니다.

## 개발하기

서버 개발에는 **Git, JDK 21, 실행 중인 Docker Desktop 또는 Docker Engine**이 필요합니다. 로컬 DB 실행에는 **Docker Compose v2**를 사용합니다. Gradle 8.14.3 Wrapper가 저장소에 포함되어 있습니다.

### 소스 빌드

```sh
git clone https://github.com/SSAFY-URAYAHA/DevLink_server.git
cd DevLink_server
./gradlew build
```

Windows PowerShell에서는 마지막 줄 대신 다음 명령을 사용합니다.

```powershell
.\gradlew.bat build
```

빌드에 테스트가 포함되며 실행 JAR은 `build/libs/app.jar`에 생성됩니다. 통합 테스트는 Testcontainers로 PostgreSQL 16을 별도로 실행하므로 Docker가 필요합니다. 테스트에는 `application-test.yml`을 사용하며 운영 DB·외부 LLM API 키·배포용 Secrets는 필요하지 않습니다.

### 로컬 서버 실행

[README의 빠른 시작](README.ko.md#빠른-시작)에 따라 `.env`를 준비하고, `TASK_MESSAGE_CURSOR_SECRET`에 32바이트 이상의 무작위 서명키를 설정합니다. 서버 컨테이너가 이미 실행 중이면 중지한 뒤 DB만 실행합니다.

```sh
docker compose stop app
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

Windows PowerShell에서는 마지막 줄 대신 다음 명령을 사용합니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

서버·DB를 모두 컨테이너로 실행하거나 웹·브로커를 연결하는 방법은 [README](README.ko.md)를 참고하세요.

### 저장소 구조

```text
DevLink_server/
├── src/main/java/com/devlink/server/
│   ├── controller/    # REST API 진입점
│   ├── service/       # 비즈니스 로직과 트랜잭션
│   ├── domain/        # 엔티티·상태·인증 Principal
│   ├── dto/           # 요청·응답 객체
│   ├── repository/    # 데이터 접근
│   ├── config/        # Security·WebSocket·JPA·스케줄러 설정
│   ├── filter/        # Agent 인증
│   ├── handler/       # WebSocket·예외·요청 본문 처리
│   ├── realtime/      # 연결 관리·실시간 이벤트
│   ├── client/        # Context 추론 구현
│   └── prompt/        # Context 프롬프트
├── src/main/resources/
│   ├── application*.yml
│   └── db/migration/  # Flyway SQL
├── src/test/          # 단위·통합 테스트
├── deploy/            # 운영 Compose·Caddy·환경 변수 예시
├── docs/              # 설계·구현·검증 문서
└── .github/           # CI/CD·Issue·PR 템플릿
```

### 검사와 테스트

저장소 루트에서 실행합니다.

```sh
./gradlew test
./gradlew build
```

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

`build`에는 테스트가 포함되므로 테스트를 먼저 따로 실행할 필요는 없습니다. 테스트만 확인할 때는 `test`를 사용합니다. 보고서는 `build/reports/tests/test/index.html`에서 확인할 수 있습니다. CI도 Gradle 빌드에 포함된 테스트를 실행합니다.

### 코드와 DB 변경

- 기존 패키지 구조와 클래스 명명 방식을 따릅니다. 요청·응답은 DTO, 비즈니스 로직과 트랜잭션은 서비스, 데이터 접근은 리포지토리의 역할에 맞게 배치합니다.
- 동작이 바뀌면 관련 테스트로 검증하고, API·인증·설정이 바뀌면 관련 문서도 함께 갱신합니다.
- DB 스키마 변경은 `src/main/resources/db/migration`에 `V{순번}__{설명}.sql` 형식의 새 Flyway 마이그레이션으로 추가합니다.
- 공유·운영 DB에 이미 적용한 마이그레이션은 수정하지 않습니다. 엔티티와 SQL의 타입·길이·널 허용을 함께 확인합니다.

## Git Flow

[Notion Git Flow 전략](https://www.notion.so/fdd35a4f3ca6837fb6ef8101afa7fb68)을 기준으로 브랜치를 관리합니다.
Notion 문서에 혼용된 `main`과 `master`는 이 저장소의 운영 브랜치인 `master`로 통일합니다.

### 브랜치 역할

| 브랜치 | 역할 |
| --- | --- |
| `master` | 배포 가능한 운영 버전 |
| `develop` | 다음 배포를 위한 개발 통합 |
| `feature/*` | 기능 개발 |
| `release/*` | 배포 준비 및 최종 수정 |
| `hotfix/*` | 운영 환경 긴급 수정 |

- `master`, `develop`에는 직접 푸시하지 않고 Pull Request(PR)를 통해 반영합니다.
- `feature/*`는 `develop`에서 분기하고, 하나의 기능이 완성되면 `develop`으로 PR을 생성합니다.
- `release/*`는 `develop`에 배포할 기능이 모이면 생성합니다. 배포 준비 중 발생한 버그는 해당 브랜치에서 수정하고 `develop`에도 반영합니다. 배포 가능한 상태가 되면 `master`와 `develop` 양쪽으로 PR을 생성합니다.
- `hotfix/*`는 `master`에서 분기해 긴급 버그를 수정합니다. 수정이 완료되면 `master`와 `develop` 양쪽으로 PR을 생성합니다.

### 브랜치 명명 규칙

```text
{type}/{scope}/{description}
```

- `type`: `feature`, `release`, `hotfix`.
- `scope`: 아래 작업 영역 표를 사용합니다.
- 배포 브랜치는 `release/{version}` 형식을 사용합니다.

| Scope | 대상 |
| --- | --- |
| `front` | Frontend |
| `back` | Backend |
| `ai` | AI / ML |
| `data` | 데이터 수집 / 전처리 / ETL |
| `bigdata` | 대용량 데이터 처리 |
| `spark` | Spark / 분산 처리 |
| `kafka` | Kafka / 분산 처리 |
| `infra` | Docker / 서버 / 배포 환경 |
| `common` | 공통 작업 |

```text
feature/back/auth-api
feature/back/readme
feature/infra/build-cache
release/1.0.0
hotfix/back/auth-error
```

### 커밋 규칙

```text
type(scope): message
```

커밋의 `scope`는 브랜치 명명 규칙의 작업 영역과 동일하게 사용합니다.

| Type | 설명 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 코드 구조 개선 |
| `perf` | 성능 개선 |
| `docs` | 문서 수정 |
| `test` | 테스트 추가 / 수정 |
| `style` | 동작에 영향을 주지 않는 코드 수정 |
| `chore` | 설정 / 패키지 / 빌드 / 기타 작업 |
| `data` | 데이터셋 / 전처리 변경 |

### Pull Request 규칙

PR은 하나의 기능 또는 작업 단위로 생성하고, 제목은 커밋과 동일한 `type(scope): message` 형식을 사용합니다. 본문은 저장소의 [PR 템플릿](.github/pull_request_template.md)을 사용합니다.

```text
feature/back/auth-api → develop
feature/infra/build-cache → develop

release/1.0.0 → master
release/1.0.0 → develop

hotfix/back/auth-error → master
hotfix/back/auth-error → develop
```

PR 제목 예시:

```text
feat(back): Agent 연결 토큰 발급 구현
docs(back): 오픈소스 사용자를 위한 README 작성
perf(back): 작업 목록 조회 쿼리 개선
```

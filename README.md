# DevLink 중앙 서버

DevLink 중앙 서버 단독 저장소입니다. Java 21 · Spring Boot 3.5.16 · Gradle(Groovy DSL) · PostgreSQL 16 · Flyway 단일 모듈이며, 인증·팀·Agent / 작업 생성·상태 / 작업 결과·조회 / 실시간 연결·라우팅 담당자가 기능을 붙여 나갈 공통 기반입니다.

> **현재 미확정**: ERD, 인증 방식(세션/JWT). 확정 전까지 마이그레이션·엔티티·인증 필터를 추가하지 않습니다. 비즈니스 로직은 아직 없습니다.

## 1. 필요 환경

- Java 21
- 실행 중인 Docker Desktop 또는 Docker Engine (로컬 DB와 테스트 모두 사용)

## 2-1. 로컬 실행 방법 - PC 실행
#### 1) env 파일 생성
```sh
cp .env.example .env
```
#### 2) postgres 컨테이너 실행
```bash
docker compose up -d postgres
```
#### 3) PC 직접 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

## 2-2. 로컬 실행 방법 - Docker 실행
#### 1) env 파일 생성
```sh
cp .env.example .env
```

#### 2) 컨테이너 한번에 실행
```bash
docker compose up -d --build
```

## 2-3. 헬스체크 요청

```sh
curl -i http://localhost:8080/api/v1/health   # 200, {"success":true,"data":"UP",...}
curl -i http://localhost:8080/api/v1/teams    # 401 JSON (리다이렉트 없음)
```

## 2-4. 테스트 · 빌드

```sh
./gradlew test
./gradlew build
```

- 테스트는 Testcontainers로 PostgreSQL 16 컨테이너를 직접 띄웁니다. Docker가 없으면 명확한 오류로 실패합니다.
- 새 테스트는 `AbstractIntegrationTest`를 상속합니다.
- 결과물: `build/libs/app.jar`

## 3. 환경 변수

| 변수 | local 기본값 | 용도 |
| --- | --- | --- |
| DB_URL | jdbc:postgresql://localhost:5432/devlink | JDBC URL (앱 컨테이너는 postgres 호스트) |
| DB_USERNAME | devlink | PostgreSQL 사용자 |
| DB_PASSWORD | devlink | PostgreSQL 비밀번호 |
| CORS_ALLOWED_ORIGINS | https://devlinkfrontend.vercel.app,https://devlink.ai.kr | REST API 허용 Origin (쉼표 구분, 쿠키 포함 요청 허용) |
| WS_ALLOWED_ORIGINS | http://localhost:3000 | WebSocket 허용 Origin (쉼표 구분) |

prod 프로필의 DB·WebSocket 설정은 기본값 없이 작업 디렉터리의 `.env` 또는 환경 변수에서 값을 받습니다: `java -jar build/libs/app.jar --spring.profiles.active=prod`. `.env`는 커밋하지 않습니다. 운영 키 목록은 [deploy/.prod.env.example](deploy/.prod.env.example)에 있으며 실제 값은 GitHub Secret `PROD_ENV`로 관리합니다.

REST CORS는 위의 두 HTTPS Origin을 기본으로 허용합니다. `CORS_ALLOWED_ORIGINS`는 선택 설정이므로 기존 `PROD_ENV`에 키가 없어도 기본값이 적용됩니다. 다른 주소를 추가하려면 허용할 주소 전체를 쉼표로 구분해 지정합니다. 로컬 프론트엔드를 사용할 때도 해당 Origin을 명시적으로 추가합니다. CORS는 `/api/**`에만 적용하며 WebSocket 허용 Origin은 별도 설정입니다.

## 4. 패키지 구조

루트 패키지는 `com.devlink.server`이며, `domain`은 엔티티별 하위 패키지로 구성합니다.

| 패키지 | 역할 |
| --- | --- |
| config | Security, WebSocket, JPA Auditing 설정 |
| common | 공통 응답, 오류 코드, 비즈니스 예외 |
| controller | REST API 진입점 |
| domain | JPA 엔티티, 상태 enum, BaseTimeEntity |
| dto | 요청·응답 DTO |
| filter | 인증·Agent 토큰 서블릿 필터 (추후) |
| handler | 전역 예외, 인증 실패·권한 거부, WebSocket 처리 |
| repository | Spring Data JPA 리포지토리 |
| service | 비즈니스 로직과 트랜잭션 |

클래스 이름은 기능명 접두사를 씁니다: `AuthController`, `TeamService`, `AgentRepository`, `TaskCreateRequest`.

```text
domain/
├── user/
│   └── User.java
├── team/
│   └── Team.java
├── teammember/
│   ├── TeamMember.java
│   └── MemberRole.java
├── agent/
│   ├── Agent.java
│   ├── AgentStatus.java
│   └── ToolType.java
├── task/
│   ├── Task.java
│   └── TaskStatus.java
├── taskfile/
│   ├── TaskFile.java
│   └── ChangeType.java
└── taskevent/
    ├── TaskEvent.java
    └── TaskEventType.java
```

각 엔티티와 해당 엔티티에서 사용하는 enum을 같은 패키지에 배치하고, 리포지토리는 `com.devlink.server.repository`에서 관리합니다.

## 5. 마이그레이션 규칙

- 경로 `src/main/resources/db/migration`, 이름 `V{정수 순번}__{설명}.sql`. 날짜 버전은 쓰지 않습니다.
- 순번이 충돌하면 리베이스 후 아직 적용하지 않은 파일의 번호를 다시 매깁니다.
- **공유 DB(dev 서버 등)에 적용된 마이그레이션은 수정 금지**. 로컬에만 적용된 것은 `docker compose down -v`로 초기화 후 다시 작성할 수 있습니다.
- 모든 프로필에서 `ddl-auto=validate`, Flyway `validate-on-migrate=true`, `baseline-on-migrate=false`. prod는 `clean-disabled=true`.
- 엔티티는 마이그레이션 스키마의 타입·길이·널 허용과 일치시킵니다. `updated_at`은 BaseTimeEntity Auditing으로 채웁니다.

## 6. 확정 후 수정할 지점

**ERD 확정 시**
- `V1__init_schema.sql` 추가 (TIMESTAMPTZ + Instant, 모든 FK 인덱스, Agent 토큰 해시만 저장, 작업 상태 CHECK, 멱등 키 UNIQUE)
- `domain/`에 엔티티 추가
- `DevLinkServerIntegrationTest`의 "마이그레이션·테이블 없음" 단정을 V1 적용·테이블 존재 검증으로 교체

**인증 방식 확정 시**
- `SecurityConfig` TODO 두 곳 (세션/CSRF 정책, 인증 필터 연결)
- `filter/`에 인증 필터·Agent 토큰 필터 추가
- `WebSocketConfig` 핸드셰이크 인증, STOMP 여부
- `.env.example`, `deploy/.prod.env.example`, Secret `PROD_ENV`, `deploy/docker-compose.prod.yml`에 쿠키 또는 JWT 변수 추가
- 세션 또는 리프레시 토큰 테이블 마이그레이션
- [보안 전략 문서 4절](docs/security-strategy.md#4-인증-방식-확정-후-결정할-사항) 갱신

## 7. CI/CD · 배포

워크플로우: [.github/workflows/ci-cd.yml](.github/workflows/ci-cd.yml)

| 이벤트 | 동작 |
| --- | --- |
| develop·master 푸시, PR | `./gradlew build` (통합 테스트 포함) |
| master 푸시 | 위 통과 후 Docker 이미지를 `ghcr.io/ssafy-urayaha/devlink-server`에 push → SSH로 EC2 접속 → `docker compose pull && up -d` |

EC2에는 Docker만 있으면 됩니다. compose 파일([deploy/docker-compose.prod.yml](deploy/docker-compose.prod.yml)), Caddy 설정([deploy/Caddyfile](deploy/Caddyfile)), `.env`는 배포 때마다 워크플로우가 `/opt/devlink`에 써 넣습니다.

**HTTPS**: `api.devlink.ai.kr`(A 레코드 → EC2 퍼블릭 IP)로 들어온 요청을 Caddy 컨테이너가 80·443에서 받아 Let's Encrypt 인증서를 자동 발급·갱신하고 `app:8080`으로 프록시합니다. 앱 컨테이너의 8080은 compose 네트워크 안에서만 접근하며 외부에 노출하지 않습니다. 인증서는 `caddy_data` 볼륨에 저장되므로 재배포해도 재발급하지 않습니다. 이 볼륨을 지우면 Let's Encrypt 발급 횟수 제한에 걸릴 수 있으니 주의합니다. 앱은 prod 프로필에서 `server.forward-headers-strategy=framework`로 프록시가 전달한 `X-Forwarded-*` 헤더를 신뢰합니다.

**GitHub Secrets** (Settings → Secrets and variables → Actions)

| Secret | 값 |
| --- | --- |
| EC2_HOST | EC2 호스트명 |
| EC2_SSH_KEY | pem 파일 내용 전체 (BEGIN~END) |
| GHCR_PAT | `read:packages` 권한의 GitHub Personal Access Token (EC2가 비공개 이미지를 받을 때 사용) |
| PROD_ENV | `deploy/.prod.env.example` 형식의 운영 환경 변수 전체 |
| DISCORD_WEBHOOK_URL | Discord 웹후크 URL 원본 (`/github` 접미사 없이). 없으면 알림만 생략되고 배포는 진행 |

**EC2 1회 설정**

```sh
sudo apt update && sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker ubuntu
sudo mkdir -p /opt/devlink && sudo chown ubuntu:ubuntu /opt/devlink
```

재접속 후 `docker ps`가 sudo 없이 되면 준비 완료입니다. 보안 그룹은 22, 80, 443 인바운드가 필요합니다. 서버에 ufw가 켜져 있으면 `sudo ufw allow 80/tcp && sudo ufw allow 443/tcp`로 같은 포트를 엽니다. 80은 인증서 발급(ACME HTTP 챌린지)과 HTTPS 리다이렉트에 사용합니다.

**배포 확인**

```sh
curl -i https://api.devlink.ai.kr/api/v1/health   # 200, Caddy 경유
```

인증서가 발급되지 않으면 EC2에서 `docker compose -f /opt/devlink/docker-compose.prod.yml logs caddy`로 원인을 확인합니다. 대부분 DNS 미전파이거나 80 포트가 닫힌 경우입니다.

**운영 변수 추가 순서**: `deploy/.prod.env.example`에 키 추가 → Secret `PROD_ENV`에 값 추가 → `docker-compose.prod.yml`의 `app.environment`에 전달 추가 → master 푸시. 키가 빠지면 배포 잡의 "PROD_ENV 키 검사" 단계에서 멈춥니다.

**운영 DB 초기화**가 필요하면 EC2에서 `docker compose -f /opt/devlink/docker-compose.prod.yml down -v`를 실행합니다. 공유 DB이므로 팀 합의 후에만 합니다.

## 8. 참고 문서

- [검증 기록](docs/verification.md): 직접 실행해 확인한 결과
- [보안 전략](docs/security-strategy.md)

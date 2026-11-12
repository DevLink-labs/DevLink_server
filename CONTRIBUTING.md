# Contributing Guide

**English** · [한국어](CONTRIBUTING.ko.md)

[Project overview](README.md) · [Community code of conduct](CODE_OF_CONDUCT.md) · [Security policy](SECURITY.md)

Bug reports, documentation improvements, and feature proposals for DevLink Server are welcome. This is a basic guide to the server repository's development environment and existing branch, commit, and PR conventions. More detailed contribution procedures will be added later.

## Getting started

- Read the [community code of conduct](CODE_OF_CONDUCT.md).
- Submit general bugs and feature proposals through [Issues](https://github.com/SSAFY-URAYAHA/DevLink_server/issues). Bug reports should include your environment, reproduction steps, expected behavior, and actual behavior.
- Follow the [security policy](SECURITY.md) to report vulnerabilities instead of using a public Issue.
- Describe the purpose and scope of your changes, related Issues, checks and tests you ran, and their results in the PR. Do not include passwords, tokens, or personal configuration files.

## Development

Server development requires **Git, JDK 21, and a running Docker Desktop or Docker Engine**. Use **Docker Compose v2** to run the local database. The repository includes the Gradle 8.14.3 Wrapper.

### Build from source

```sh
git clone https://github.com/SSAFY-URAYAHA/DevLink_server.git
cd DevLink_server
./gradlew build
```

In Windows PowerShell, replace the last line with:

```powershell
.\gradlew.bat build
```

The build includes tests and produces the executable JAR at `build/libs/app.jar`. Integration tests start a separate PostgreSQL 16 container with Testcontainers, so Docker must be running. Tests use `application-test.yml` and do not require a production database, external LLM API keys, or deployment secrets.

### Run the server locally

Prepare `.env` using the [README quick start](README.md#quick-start), including a random signing key of at least 32 bytes for `TASK_MESSAGE_CURSOR_SECRET`. If the app container is already running, stop it and run only the database:

```sh
docker compose stop app
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

In Windows PowerShell, replace the last line with:

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

See the [README](README.md) for running both the server and database in containers, or connecting the web interface and broker.

### Repository structure

```text
DevLink_server/
├── src/main/java/com/devlink/server/
│   ├── controller/    # REST API entry points
│   ├── service/       # Business logic and transactions
│   ├── domain/        # Entities, states, and authentication principals
│   ├── dto/           # Request and response objects
│   ├── repository/    # Data access
│   ├── config/        # Security, WebSocket, JPA, and scheduler configuration
│   ├── filter/        # Agent authentication
│   ├── handler/       # WebSocket, exception, and request body handling
│   ├── realtime/      # Connection management and real-time events
│   ├── client/        # Context inference implementations
│   └── prompt/        # Context prompts
├── src/main/resources/
│   ├── application*.yml
│   └── db/migration/  # Flyway SQL
├── src/test/          # Unit and integration tests
├── deploy/            # Production Compose, Caddy, and environment examples
├── docs/              # Design, implementation, and verification guides
└── .github/           # CI/CD and Issue/PR templates
```

### Checks and tests

Run these commands from the repository root:

```sh
./gradlew test
./gradlew build
```

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Because `build` includes tests, you do not need to run `test` separately first. Use `test` when you only want to run tests. Reports are available at `build/reports/tests/test/index.html`. CI also runs the tests included in the Gradle build.

### Code and database changes

- Follow the existing package structure and class naming conventions. Keep request and response objects in DTOs, business logic and transactions in services, and data access in repositories.
- Verify behavior changes with relevant tests. Update related documentation when APIs, authentication, or configuration change.
- Add database schema changes as new Flyway migrations under `src/main/resources/db/migration`, using the filename format `V{number}__{description}.sql`.
- Do not modify migrations already applied to shared or production databases. Check types, lengths, and nullability in both entities and SQL.

## Git Flow

Branches follow the [Notion Git Flow strategy](https://www.notion.so/fdd35a4f3ca6837fb6ef8101afa7fb68).
Where the Notion document uses both `main` and `master`, this repository consistently uses `master` as its production branch.

### Branch roles

| Branch | Purpose |
| --- | --- |
| `master` | Production-ready version |
| `develop` | Integration for the next release |
| `feature/*` | Feature development |
| `release/*` | Release preparation and final fixes |
| `hotfix/*` | Urgent production fixes |

- Do not push directly to `master` or `develop`; use Pull Requests (PRs).
- Create `feature/*` branches from `develop` and open a PR to `develop` when the feature is complete.
- Create `release/*` branches when `develop` has accumulated the features to release. Fix release-preparation bugs on the release branch and also apply them to `develop`. When ready to release, open PRs to both `master` and `develop`.
- Create `hotfix/*` branches from `master` for urgent fixes. When complete, open PRs to both `master` and `develop`.

### Branch naming

```text
{type}/{scope}/{description}
```

- `type`: `feature`, `release`, or `hotfix`.
- `scope`: use the work areas listed below.
- Release branches use `release/{version}`.

| Scope | Area |
| --- | --- |
| `front` | Frontend |
| `back` | Backend |
| `ai` | AI / ML |
| `data` | Data collection / preprocessing / ETL |
| `bigdata` | Large-scale data processing |
| `spark` | Spark / distributed processing |
| `kafka` | Kafka / distributed processing |
| `infra` | Docker / servers / deployment infrastructure |
| `common` | Shared work |

```text
feature/back/auth-api
feature/back/readme
feature/infra/build-cache
release/1.0.0
hotfix/back/auth-error
```

### Commit conventions

```text
type(scope): message
```

Use the same work areas for commit scopes as for branch naming.

| Type | Meaning |
| --- | --- |
| `feat` | Add a feature |
| `fix` | Fix a bug |
| `refactor` | Improve code structure |
| `perf` | Improve performance |
| `docs` | Update documentation |
| `test` | Add or update tests |
| `style` | Change code without affecting behavior |
| `chore` | Configuration, packages, builds, and other maintenance |
| `data` | Dataset or preprocessing changes |

### Pull Request conventions

Each PR should cover one feature or unit of work. Use the same `type(scope): message` format for the title as for commits. Use the repository's [PR template](.github/pull_request_template.md) for the body.

```text
feature/back/auth-api → develop
feature/infra/build-cache → develop

release/1.0.0 → master
release/1.0.0 → develop

hotfix/back/auth-error → master
hotfix/back/auth-error → develop
```

Example PR titles:

```text
feat(back): implement Agent connection token issuance
docs(back): write README for open-source users
perf(back): improve task list queries
```

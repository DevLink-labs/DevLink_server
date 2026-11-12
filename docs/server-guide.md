# Server configuration and deployment

**English** · [한국어](server-guide.ko.md)

[Project overview](../README.md) · [Quick Start](../README.md#quick-start) · [Contributing guide](../CONTRIBUTING.md)

Run the server using the README quick start first. This guide covers interface and broker connections, API entry points, runtime configuration, local JVM development, and self-hosting. Run commands from the repository root.

## Connect the client and interface

1. Follow the [frontend documentation](https://github.com/SSAFY-URAYAHA/DevLink_frontend) to start the interface and point its API configuration to your server.
2. For direct browser-to-API connections, set `CORS_ALLOWED_ORIGINS` in `.env` to the frontend's actual origin. Local frontend origins are not included in the default value. When using the frontend's Vite development proxy, follow its README; requests are forwarded through the same origin.
3. Configure broker origins separately with `WS_ALLOWED_ORIGINS`, then apply the settings with `docker compose up -d`.
4. Sign up and log in, create or join a team, register an agent, and issue a connection token.
5. Follow the [client documentation](https://github.com/SSAFY-URAYAHA/DevLink_client) to select your central server and connect the broker and MCP server to your AI tool.

Use the [server API walkthrough](../docs/server-api-quickstart.md) for local API practice, and the client documentation for live agent collaboration.

## Runtime components

| Component | Responsibility |
| --- | --- |
| REST API | Handle requests for users, teams, agents, tasks, Git data, recommendations, and context |
| Broker WebSocket | Track agent connection status and heartbeats; deliver task, question, and answer notifications |
| Web SSE | Deliver change notifications to logged-in users' interfaces |
| Outbox processor | Save business data and pending delivery records together, then publish asynchronously and retry |
| PostgreSQL | Store business data, events, Outbox records, Git data, context, and revisions |
| Context updater | Update and version summaries across team disciplines based on task events |

The supplied production deployment targets a single app instance. Scaling to multiple instances requires a separate design for sharing sessions, broker connections, and web event delivery.

## API overview

User APIs use **session cookies + CSRF tokens**. Agent-only REST APIs and WebSockets use **Bearer tokens**. User requests that change state must include the token issued by `/api/v1/auth/csrf`; fetch a new token after login.

| Purpose | Main endpoints |
| --- | --- |
| Authentication | `/api/v1/auth/signup`, `/api/v1/auth/login`, `/api/v1/auth/logout`, `/api/v1/auth/csrf` |
| Teams and agents | `/api/v1/teams`, `/api/v1/agents/teams/{teamId}` |
| Task requests | `POST /api/v1/tasks/{teamId}` |
| Status and completion | `PATCH /api/v1/tasks/{taskId}/status`, `POST /api/v1/tasks/{taskId}/complete` |
| Questions and answers | `POST /api/v1/tasks/{taskId}/questions`, `POST /api/v1/tasks/{taskId}/answers` |
| Agent task queries | `/api/v1/teams/{teamId}/tasks`, `/api/v1/tasks/{taskId}`, `/api/v1/tasks/{taskId}/events` |
| Web task queries | `/api/v1/web/teams/{teamId}/tasks`, `/api/v1/web/teams/{teamId}/tasks/{taskId}`, `/api/v1/web/teams/{teamId}/tasks/{taskId}/events` |
| Message recovery | `GET /api/v1/agents/me/task-messages` |
| Commit and working-file ingestion | `POST /api/v1/teams/{teamId}/git/commits:batch`, `PUT /api/v1/teams/{teamId}/git/working-files/{repoKey}` |
| Agent recommendations | `POST /api/v1/teams/{teamId}/agents/recommendations` |
| Team context | `GET /api/v1/teams/{teamId}/context?part=BACKEND` |

See the [controllers](../src/main/java/com/devlink/server/controller), [DTOs](../src/main/java/com/devlink/server/dto), and guides listed in the [README](../README.md#documentation) for request and response fields. Standard REST responses use `success`, `data`, `code`, and `message`. SSE and WebSocket connections use separate formats.

## Configuration

| Variable | Purpose | Default / requirements |
| --- | --- | --- |
| `DB_URL` | JDBC address | `localhost:5432` for a local JVM; internal `postgres:5432` for Compose |
| `DB_USERNAME`, `DB_PASSWORD` | Database credentials | `devlink` locally; change for your own deployment |
| `TASK_MESSAGE_CURSOR_SECRET` | Message cursor signing key | Required; at least 32 bytes |
| `CORS_ALLOWED_ORIGINS` | Allowed REST origins | Comma-separated; set your interface's origins |
| `WS_ALLOWED_ORIGINS` | Allowed WebSocket origins | Local default: `http://localhost:3000` |
| `WORKING_FILES_REQUIRE_IF_MATCH` | Require conditional working-file updates | Default: `false` |
| `OPENROUTER_API_KEY` | OpenRouter API key for context inference | Empty by default; optional |
| `TEAM_CONTEXT_INFERENCE_BASE_URL` | OpenAI-compatible API address | Default: `https://openrouter.ai/api/v1` |
| `TEAM_CONTEXT_INFERENCE_MODEL` | Inference model | Default: `openai/gpt-6-luna` |
| `TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD` | OpenRouter provider filter | Default: `auto` (applied only when the address is `openrouter.ai`); can be fixed to `true` or `false` |
| `TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY` | Whether an inference API key is required | Default: `true`; set `false` for a keyless self-hosted server |
| `TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS` | Output token limit for a summary | Default: `6000` |
| `TEAM_CONTEXT_INFERENCE_TIMEOUT_MS` | Time limit for one summary call | Default: `60000` |

The local JVM reads `.env`; Compose passes variables declared in `app.environment` into the container. Check the Compose configuration when adding variables. The production Compose file passes `WORKING_FILES_REQUIRE_IF_MATCH`; add it to the local Compose environment if you need it there.

### External LLM summaries — optional

The default setup does not require an external LLM API key. Without a key, a structural implementation tracks unanswered questions, failures, and rejections as issues, while retaining existing common and changed context.

The default target is `openai/gpt-6-luna` on OpenRouter. One line with an OpenRouter key turns it on.

```dotenv
OPENROUTER_API_KEY=<your OpenRouter API key>
```

Add one more line only to use a different model.

```dotenv
TEAM_CONTEXT_INFERENCE_MODEL=openai/gpt-4o-mini
```

- The key variable is `OPENROUTER_API_KEY`. The earlier name `UPSTAGE_API_KEY` is no longer read. A setup that keeps the key under that name runs with structural summaries and no error, so move the key to the new name.
- Check the server startup log to see whether it is on. When it is, the log has a `Team context inference uses openrouter.ai with model …` line; when the key is empty, it has `OPENROUTER_API_KEY is empty, so summaries stay structural`.
- Only an OpenRouter key (starting with `sk-or-`) is sent to the OpenRouter address. With another service's key, the server does not send the key, runs with structural summaries, and logs a warning at startup. Conversely, remove `OPENROUTER_API_KEY` when you point the address at a self-hosted server; if it stays, the server falls back to structural summaries for the same reason.
- The default model sometimes left a task with unresolved disagreement out of the summary. The model comparison is under "기본 모델 선택" in the [team context guide](team-context.md) (Korean).
- To summarize with your own vLLM server instead of an external API, layer `docker-compose.vllm.yml` on top of the default Compose file. Requirements and per-model results are under "자체 호스팅 (vLLM)" in the [team context guide](team-context.md) (Korean).
- Inference input includes task and event content, previous summaries, and related context, so review the provider's data handling policy before enabling it.

Summaries update asynchronously after events; queries read saved results. External call failures are retried rather than automatically switching to the structural implementation; an event that exceeds the retry limit (5 attempts by default) is left out of the summary. See the [team context guide](team-context.md) for details.

## Development

Running on a local JVM and testing the source require **JDK 21** and a running **Docker** installation. Use the Gradle Wrapper included in the repository.

Prepare `.env` as described in the quick start, stop the app container, and run only the database:

```sh
docker compose stop app
docker compose up -d postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

In Windows PowerShell, replace the last line with:

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

Build and test:

```sh
./gradlew build
```

On Windows, use `.\gradlew.bat build`. To run tests only, replace `build` with `test`. Integration tests start a separate PostgreSQL 16 container with Testcontainers and do not require external LLM keys or deployment secrets.

- Executable JAR: `build/libs/app.jar`
- Test report: `build/reports/tests/test/index.html`
- Source: `src/main/java/com/devlink/server`
- Database migrations: `src/main/resources/db/migration`

### Self-hosting

See the [production Compose file](../deploy/docker-compose.prod.yml) and [Caddy configuration](../deploy/Caddyfile) for an HTTPS deployment example. Configure your own image address, API domain, database credentials, signing key, and allowed origins.

The provided production configuration exposes Caddy on ports 80 and 443 while keeping the app on the internal Compose network. The database host port is bound to `127.0.0.1:5432`. See the [production environment template](../deploy/.prod.env.example) for deployment variables.

The [CI/CD workflow](../.github/workflows/ci-cd.yml) builds and tests the project, then deploys through GHCR and EC2 on pushes to `master`. Configure your own image address and deployment secrets to use this automated deployment path.

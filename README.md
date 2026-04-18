# Kukuvaia

> _κουκουβάγια_ — owl, symbol of wisdom.

An extensible conversational AI agent platform. Kukuvaia is a self-hosted, open-source stack (Apache 2.0) that turns any OpenAI-compatible LLM into a tool-using agent with persistent memory, planning, personas, and user-extensible skills.

This repository hosts three independently-deployed components:

| Component | Language | Role |
|-----------|----------|------|
| **`kukuvaia-engine`** | Java 21 + Kotlin 2.1 (Spring Boot + Spring AI) | Agent engine — LLM conversation, tool execution, sessions, memory, planning, Web API (HTTP/SSE) |
| **`kukuvaia-cli`** | Go 1.24 (Charm stack) | Terminal UI client — Bubbletea + Lipgloss, connects to the engine over HTTP/SSE |
| **`kukuvaia-admin`** | TypeScript (React 19 + Vite) | Admin web UI — provider/model management, dashboards, settings |

---

## Prerequisites

Install these on your development machine. Versions below are the known-good set used by the project; older releases may work but are not tested.

### Runtime toolchains

| Tool | Version | Why | Install (Ubuntu/Debian) |
|------|---------|-----|--------------------------|
| **Java JDK** | 21+ | `kukuvaia-engine` modules target Java 21 | `sudo apt install openjdk-21-jdk` or [SDKMAN!](https://sdkman.io/): `sdk install java 21.0.5-tem` |
| **Go** | 1.24.2+ | `kukuvaia-cli` module | `sudo apt install golang-go` or download from [go.dev](https://go.dev/dl/) |
| **Node.js** | 20 LTS or 22 LTS | `kukuvaia-admin` (React + Vite) | [`nvm install 22`](https://github.com/nvm-sh/nvm) |
| **Docker** | 24+ with Compose v2 | Runs PostgreSQL (`pgvector/pgvector:pg17`) | `sudo apt install docker.io docker-compose-plugin` |
| **Git** | 2.34+ | Source control | `sudo apt install git` |

Gradle is **not** required system-wide — the engine uses the Gradle wrapper (`./gradlew`).

### Databases

| Database | Version | Purpose | Required? |
|----------|---------|---------|-----------|
| **PostgreSQL** | 17 with `pgvector` extension | Sessions, chat memory, plans, memories, embeddings | Yes |
| **MongoDB** | 6.0+ | Legacy read-only data (optional, only if connecting to ETSL via MCP) | No |

The easiest way is Docker. A minimal PG container:

```bash
docker run -d --name kukuvaia-postgres \
  -e POSTGRES_USER=kukuvaia \
  -e POSTGRES_PASSWORD=kukuvaia \
  -e POSTGRES_DB=kukuvaia \
  -p 5432:5432 \
  pgvector/pgvector:pg17
```

### LLM Provider

Kukuvaia talks to any OpenAI-compatible endpoint via Spring AI's `OpenAiApi`. You need **one** of:

- **OpenAI** — an API key from https://platform.openai.com/api-keys
- **Anthropic via OpenAI-compatible proxy** (e.g., [LiteLLM](https://github.com/BerriAI/litellm), [OpenRouter](https://openrouter.ai))
- **GitHub Copilot** — OAuth device flow, free with active Copilot subscription (handled by the engine's `/login github` flow)
- **SmartGate** — corporate gateway with JWT auth (internal Schibsted; skip if you are outside that network)
- **Local models** via [Ollama](https://ollama.com) or [LM Studio](https://lmstudio.ai) — any that expose the OpenAI protocol

---

## Project Layout

```
kukuvaia/
├── kukuvaia-engine/      # Java/Spring Boot agent engine
│   ├── kukuvaia-core/    # Domain logic, tools, API, security
│   ├── kukuvaia-agents/  # Kotlin + Embabel GOAP planning
│   ├── kukuvaia-memory/  # pgvector memory subsystem
│   └── kukuvaia-app/     # Spring Boot entry point + Flyway migrations
├── kukuvaia-cli/         # Go TUI client (Bubbletea)
├── kukuvaia-admin/       # React admin web UI (Vite)
├── kukuvaia-theme.yaml   # Shared design tokens (CLI + web)
└── docs/                 # Architecture, plans, analyses, decisions
```

---

## First-time Setup

Run these four steps once. After that, jump to **Daily Development** below.

### 1. Bootstrap `.env`

The engine reads configuration from environment variables. For local development, keep them in `kukuvaia-engine/.env` — `./gradlew :kukuvaia-app:bootRun` auto-loads it.

```bash
./scripts/bootstrap.sh
```

What the script does (idempotent, safe to re-run):

1. Copies `kukuvaia-engine/.env.example` → `kukuvaia-engine/.env` if `.env` does not exist.
2. Reports whether the Postgres container is running.

Alternative — manual:

```bash
cp kukuvaia-engine/.env.example kukuvaia-engine/.env
```

Then open `kukuvaia-engine/.env` and set `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`. The Postgres defaults match the Docker container in step 2 (`PG_PASSWORD=kukuvaia`).

> Skipping `.env` and starting the engine with an empty `PG_PASSWORD` is the #1 cause of `The server requested SCRAM-based authentication, but no password was provided` on first run.

### 2. Start PostgreSQL

```bash
docker compose -f kukuvaia-engine/docker-compose.yml up -d

# Verify it's up:
docker exec kukuvaia-postgres psql -U kukuvaia -d kukuvaia -c "SELECT version();"
```

Flyway will create the `kukuvaia` schema and run all migrations automatically on the first engine startup.

### 3. Build the engine

```bash
cd kukuvaia-engine
./gradlew clean build -x test          # first build pulls ~500 MB of deps
```

### 4. Install frontend & CLI deps

```bash
# CLI (Go) — single binary, no deps to install globally; go modules handle it
cd kukuvaia-cli
go generate ./...                       # generates styles from kukuvaia-theme.yaml
go build -o kukuvaia ./cmd/kukuvaia/

# Admin (Node) — installs local node_modules
cd ../kukuvaia-admin
npm install
```

---

## Daily Development

### Start everything

```bash
# Terminal 1 — PostgreSQL
docker compose -f kukuvaia-engine/docker-compose.yml up -d

# Terminal 2 — engine (port 8080); kukuvaia-engine/.env is auto-loaded
cd kukuvaia-engine
./gradlew :kukuvaia-app:bootRun

# Terminal 3 — CLI
cd kukuvaia-cli
./kukuvaia                             # REPL against http://localhost:8080

# Terminal 4 — admin UI (optional, port 5173)
cd kukuvaia-admin
npm run dev
```

### Stop everything

```bash
# Ctrl+C each terminal, then:
docker stop kukuvaia-postgres
```

---

## Environment Variables

The engine reads these at startup. For local development, put them in `kukuvaia-engine/.env` — `./gradlew :kukuvaia-app:bootRun` loads every `KEY=VALUE` line automatically (shell exports take precedence over the file). Run `./scripts/bootstrap.sh` to seed `.env` from `.env.example`. CLI variables (`KUKUVAIA_SERVER_URL`, `KUKUVAIA_PERSONA`, `KUKUVAIA_SESSION`) are consumed by the Go binary — export them from your shell, not from `.env`.

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8080` | Engine HTTP port |
| `PG_DSN` | `jdbc:postgresql://localhost:5432/kukuvaia` | PostgreSQL JDBC URL |
| `PG_USER` | `kukuvaia` | DB username |
| `PG_PASSWORD` | _(empty)_ | DB password |
| `LLM_BASE_URL` | `https://api.openai.com` | OpenAI-compatible endpoint |
| `LLM_API_KEY` | `not-used` | API key (override per provider or use credentials file) |
| `LLM_MODEL` | `claude-sonnet-4.5` | Default model name |
| `LLM_CONNECT_TIMEOUT` | `10s` | Connect timeout |
| `LLM_READ_TIMEOUT` | `120s` | Read timeout |
| `OPENAI_EMBEDDING_ENABLED` | `false` | If `true`, uses remote embeddings; else local ONNX (MiniLM-L6-v2) |
| `MEMORY_CONSOLIDATION_CRON` | `0 0 3 * * *` | Nightly memory consolidation schedule |
| `WEBHOOK_SECRET` | _(empty)_ | HMAC-SHA256 secret for `/api/webhooks/*` |
| `KUKUVAIA_SECURITY_DEV_MODE` | `true` | If `true`, relaxes auth for local dev |
| `KUKUVAIA_SERVER_URL` | `http://localhost:8080` | (CLI) engine URL |
| `KUKUVAIA_PERSONA` | _(empty)_ | (CLI) default persona name |
| `KUKUVAIA_SESSION` | _(empty)_ | (CLI) resume this session ID |

Local embedding model auto-downloads from Hugging Face on first run (~90 MB, cached).

---

## Verifying the Install

```bash
# Engine alive
curl -s http://localhost:8080/api/sessions
# → [] (empty list on a fresh DB)

# Start a chat via CLI
./kukuvaia
# Type: "hello" and press Enter — you should get a reply.

# Inspect the DB
docker exec kukuvaia-postgres psql -U kukuvaia -d kukuvaia \
  -c "SELECT table_name FROM information_schema.tables WHERE table_schema='kukuvaia';"
# → memories, sessions, spring_ai_chat_memory, plans, conversations, ...
```

---

## Running Tests

```bash
# Engine
cd kukuvaia-engine
./gradlew test                          # all modules
./gradlew :kukuvaia-core:test           # single module

# CLI
cd kukuvaia-cli
go test ./...

# Admin
cd kukuvaia-admin
npm run lint
npm run build                           # tsc + vite build
```

---

## Troubleshooting

**`The server requested SCRAM-based authentication, but no password was provided`** — you skipped step 1 of First-time Setup. Run `./scripts/bootstrap.sh` (creates `.env` with `PG_PASSWORD=kukuvaia` matching the Docker container) and restart `./gradlew :kukuvaia-app:bootRun`.

**`FATAL: role "kukuvaia" does not exist`** — the container was created with different `POSTGRES_USER`. Remove and recreate (`docker rm -f kukuvaia-postgres`) with the env vars above.

**Flyway fails with `extension "vector" is not available`** — you used plain `postgres:17` instead of `pgvector/pgvector:pg17`. Recreate the container.

**`403` on `/api/*`** — either enable dev mode (`KUKUVAIA_SECURITY_DEV_MODE=true`, default) or provide a valid Bearer token per the auth scheme.

**CLI prints escape codes like `]11;rgb:0000/0000/0000\`** — known issue when `glamour.WithAutoStyle()` queries terminal background. Fixed on `main`; rebuild the CLI.

**Engine OOM on first build** — Gradle pulls lots of deps; give it 2 GB heap: `export GRADLE_OPTS="-Xmx2g"`.

---

## Documentation

- [`docs/architecture/`](docs/architecture/) — system design, memory architecture, Embabel integration
- [`docs/plan/`](docs/plan/) — implementation plans by phase (P01 observability, P02 structured output, …)
- [`docs/analyzes/`](docs/analyzes/) — research and evaluations (Koog, model routing)
- [`.maister/docs/`](.maister/docs/) — project vision, roadmap, standards (Maister workflow index)
- [`CLAUDE.md`](CLAUDE.md) — high-level project map for AI-assisted development

Component-specific docs live under each module:
- [`kukuvaia-engine/CLAUDE.md`](kukuvaia-engine/CLAUDE.md)
- [`kukuvaia-cli/CLAUDE.md`](kukuvaia-cli/CLAUDE.md)

---

## License

Apache 2.0 (pending final file in repository root).

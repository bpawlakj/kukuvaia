# Development Roadmap

## Current State
- **Engine**: Multi-module Gradle (kukuvaia-core, kukuvaia-agents, kukuvaia-app)
- **Core** (Java 21): Spring Boot 3.4.4 / Spring AI 1.1.0 — 72 production files
- **Agents** (Kotlin): Embabel 0.3.4 — framework wired, PingAgent test passing
- **Tools**: YAML+Lua pipeline engine with hot reload, per-tool config, 6 built-in tools
- **Rules**: Structured MD rules (constraint/behavior/format) with scope (global/persona/intent)
- **Skills**: Executable skills (prompt + Lua) via /skill command
- **Database**: PostgreSQL with Flyway migrations (V1-V3)
- **LLM**: Generic provider (SmartGate, OpenAI, Copilot — any OpenAI-compatible)
- **CLI**: Plan ready, not yet implemented

---

## Milestone 1: MVP Release (Open Source Launch)

**Goal:** Minimum viable product — installable, demo-able, publishable.

### 1A. CLI Foundation `[Effort: L]`
- [ ] Go project setup (Bubbletea + Lipgloss + mcp-go)
- [ ] MCP Server with local tools (read_file, write_file, find_files, bash_run, git_status)
- [ ] SSE client for server communication
- [ ] Minimal TUI: input + chat viewport + OutputBlock rendering
- [ ] `~/.kukuvaia.yaml` config (server URL)
- [ ] Two modes: `kukuvaia` (TUI) and `kukuvaia --mcp` (headless MCP server)
- See: `project/cli-implementation-plan.md` Phase 1

### 1B. Docker Compose `[Effort: S]`
- [ ] `docker-compose.yml` — server + PostgreSQL + example tools
- [ ] `Dockerfile` for kukuvaia-server (multi-stage: build + runtime)
- [ ] Health check endpoints
- [ ] One-liner: `docker-compose up` → working agent

### 1C. README & Documentation `[Effort: M]`
- [ ] README.md with: pitch, architecture diagram, quickstart (3 steps), tool example
- [ ] GIF/video: install → add YAML tool → agent uses it (30 seconds)
- [ ] CONTRIBUTING.md — how to add tools, rules, skills
- [ ] LICENSE (Apache 2.0)
- [ ] Example tools directory with 5+ ready-to-use tools

### 1D. Git Repository Setup `[Effort: S]`
- [ ] Initialize git repo
- [ ] .gitignore (proper: .env, credentials, build, IDE files)
- [ ] GitHub Actions CI (build + test on PR)
- [ ] First tag: v0.1.0

### 1E. Server Hardening `[Effort: M]`
- [ ] Integration test: full flow (chat → tool call → response)
- [ ] Error handling in pipeline engine (Lua errors → readable messages)
- [ ] Graceful startup without LLM provider (tools/commands work, chat returns "no provider")
- [ ] API documentation (OpenAPI/Swagger for /api/* endpoints)

---

## Milestone 2: Full CLI + Polish

### 2A. CLI Full TUI `[Effort: L]`
- [ ] OutputBlock renderers (all 7 types: Text, Table, Code, Progress, Plan, Verification, Metadata)
- [ ] Session management (list, switch, new, delete)
- [ ] Keyboard shortcuts (Ctrl+C, Ctrl+L, Up/Down history)
- [ ] Streaming word-by-word rendering
- [ ] Spinner during tool calls
- [ ] kukuvaia-theme.yaml → styles.go (go generate)
- See: `project/cli-implementation-plan.md` Phase 2

### 2A.1. CLI Activity Tracker `[Effort: M]` — requires **P01 + P01 SpanEventBlock extension**
- [ ] `activity/` package: span-tree state (Bubbletea Elm)
- [ ] Flat + nested multi-role rendering (supervisor + workers via span parent/child)
- [ ] `ctrl+o` collapse/expand with sub-action list
- [ ] Spinner verbs + token/time animator
- [ ] Theme-driven styling from `kukuvaia-theme.yaml`
- See: `docs/plan/P01.1-cli-activity-tracker.md`

### 2B. Authentication `[Effort: M]`
- [ ] OAuth device code flow (GitHub Copilot)
- [ ] JWT auth (SmartGate)
- [ ] API key auth for server endpoints (production mode)
- [ ] Credential storage (~/.kukuvaia/credentials.json, chmod 600)

### 2C. Tool Ecosystem `[Effort: M]`
- [ ] 10+ built-in tools: web_search, git_diff, git_log, json_query, csv_parse, regex_extract, url_fetch, shell_run, env_read, timestamp
- [ ] Tool documentation generator (auto-generates docs from TOOL.md frontmatter)
- [ ] Tool validation command (`kukuvaia tool validate ./my-tool/`)

### 2D. MCP Integration `[Effort: M]`
- [ ] Server connects to CLI as MCP Client (local tools)
- [ ] Server connects to ETSL MCP Server (domain tools)
- [ ] MCP tool discovery at runtime (no restart)
- [ ] MCP server metadata in /help output

---

## Milestone 3: Production & Community

### 3A. Production Readiness `[Effort: L]`
- [ ] **P01 Observability** — OTel traces + Micrometer metrics + Prometheus scrape + `SpanEventBlock` SSE extension
  - See: `docs/plan/P01-observability.md` (MUST ship before P01.1)
- [ ] Helm chart for Kubernetes deployment
- [ ] Structured JSON logging (ELK/Loki compatible)
- [ ] Rate limiting per user/session
- [ ] Token budget per session with configurable limits

### 3B. Memory Architecture (kukuvaia-memory module) `[Effort: L]`
- [ ] **Phase 4A**: Create kukuvaia-memory module + V4 migration (users, sessions, conversations, memories, plans tables)
- [ ] **Phase 4B**: JsonChatMemoryRepository (JSONB conversations replacing row-by-row) + Session-User model
- [ ] **Phase 4C**: pgvector semantic search + SmartMemoryAdvisor (top-K retrieval replacing load-all)
- [ ] **Phase 4D**: Memory extraction pipeline (LLM extracts episodic/semantic/procedural facts post-session) + consolidation jobs (decay, merge, TTL)
- [ ] **Phase 4E**: Embabel agent integration with memory module
- See: [Memory Architecture](../../docs/architecture/memory-architecture.md)

### 3B2. Embabel Agent Development `[Effort: L]`
- [ ] ResearchAgent (web search + document analysis with GOAP planning)
- [ ] ValidationAgent (multi-step validation workflows)
- [ ] Autonomy mode (LLM selects/composes agents from available actions)
- [ ] MCP Server export (agents as MCP tools for external systems)
- See: [Embabel Integration](../../docs/architecture/embabel-integration.md)

### 3B3. Remaining Agent Features `[Effort: M]`
- [ ] Intent-driven tool loading (load only relevant tools per request)
- [ ] Sub-agent delegation improvements (Embabel RunSubagent replacing SubAgentFactory)

### 3C. Web Frontend `[Effort: L]`
- [ ] kukuvaia-web (React/Vue/Svelte)
- [ ] Same SSE API as CLI
- [ ] Design tokens from kukuvaia-theme.yaml → CSS variables
- [ ] Responsive layout (desktop + mobile)

### 3D. Community & Ecosystem `[Effort: M]`
- [ ] Tool marketplace / registry (share YAML+Lua tools)
- [ ] Plugin system for rules and skills
- [ ] Documentation site (GitHub Pages or Docusaurus)
- [ ] Example projects: code review agent, documentation agent, DevOps agent
- [ ] Community Discord/GitHub Discussions

### 3E. Enterprise Features `[Effort: L]`
- [ ] Multi-tenant (user isolation, per-user tool access)
- [ ] RBAC (role-based tool permissions)
- [ ] Audit trail (all tool calls, LLM interactions, user actions)
- [ ] SSO integration (SAML, OIDC)
- [ ] Data residency (configurable LLM provider per region)

---

## Milestone 4: Intelligence & Knowledge (Future)

Advanced agent capabilities — not required for MVP or production, but high-leverage once foundation is stable.

### 4A. Agent Reflection & Skill Library `[Effort: L]` — requires **P01 + P04 (memory)**
- [ ] Skill Library schema + pgvector retrieval (semantic memory extension)
- [ ] DreamingService — nightly pattern extraction from plans/conversations
- [ ] Self-verification critic (Voyager pattern) — rejects low-quality skills
- [ ] Reflexion feedback loop — track success rate, archive failing skills
- [ ] Automatic curriculum + meta-reflection tree (optional)
- See: `docs/plan/P12-agent-reflection.md`

### 4B. Obsidian Knowledge Base Integration `[Effort: S–M]` — requires **MCP Client wiring**
- [ ] MCP Client config for MCPVault / cyanheads / smart-connections
- [ ] Obsidian-aware rules in `~/.kukuvaia/rules/`
- [ ] Optional semantic-search enhancement via Smart Connections
- See: `docs/plan/P11-obsidian-integration.md`

---

## Plan → Milestone Mapping

Quick reference — which plan document belongs to which milestone:

| Plan | Milestone | Status | Depends on |
|------|-----------|--------|------------|
| P01 Observability (incl. SpanEventBlock) | 3A | Draft | none |
| **P01.1 CLI Activity Tracker** | **2A.1** | **Draft** | **P01 (SpanEventBlock extension)** |
| P02 Structured Output | 1A/2A | Draft | none |
| P03 Model Fallback Chain | 3A | Draft | P01 (for observability) |
| P04 Conversation Summarization | 3B | Draft | kukuvaia-memory module |
| P05 Eval Pipeline | 3A | Draft | P01 |
| P06 Content Moderation | 3A | Draft | none |
| P07 Cost Tracking | 3A | Draft | P01 |
| P08 Prompt Cache Optimization | 3A | Draft | P01 |
| P09 Human-in-the-Loop | 3B | Draft | P02 |
| P10 Embabel GOAP Agents | 3B2 | Draft | Embabel wired |
| P11 Obsidian Integration | 4B | Draft | MCP Client |
| P12 Agent Reflection / Dreaming | 4A | Draft | P01 + P04 |

**Critical path for live UX**: `P01` → `P01.1`. All other P0x are additive and can ship in parallel.

---

## Architecture Principles

1. **Tools are YAML, not code** — users don't need Java/Go to extend the agent
2. **Hot reload everything** — tools, rules, skills — no restart needed
3. **Provider agnostic** — any OpenAI-compatible LLM (SmartGate, Claude, GPT, local)
4. **MCP native** — standard protocol for tool integration (both client and server)
5. **Self-hosted first** — runs on your infra, your data stays with you
6. **Security by design** — sandbox (Lua), path traversal prevention, parameterized SQL, STRIDE reviewed

---

**Effort Scale**: `S`: 2-3 days | `M`: 1 week | `L`: 2+ weeks
*Last Updated: 2026-04-16*

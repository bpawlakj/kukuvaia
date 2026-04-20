# Development Roadmap

## Current State (2026-04-19)

### Engine — `kukuvaia-engine`
- **Multi-module Gradle**: `kukuvaia-core`, `kukuvaia-agents` (Kotlin), `kukuvaia-memory`, `kukuvaia-app`
- **Core** (Java 21): Spring Boot 3.4.4 / Spring AI 1.1.0
- **Observability**: Prometheus `/actuator/prometheus` + Micrometer metrics (`kukuvaia.llm.*`, `kukuvaia.tool.*`, `kukuvaia.memory.*`, `kukuvaia.daemon.*`, `kukuvaia.session.active`) — **P01 Stage A shipped**
- **Live activity stream**: `SpanEventBlock` emitted over SSE (`role:*`, `tool:*`) — **P01 Stage B shipped**
- **Agents** (Kotlin): Embabel 0.3.4 wired
- **Planning mode**: 3-phase state machine (DISCOVERY → DRAFTING → APPROVAL) with `DiscoveryFacts` (known / excluded / gaps / ambiguities), hard constraints in DRAFTING, empirical extraction — **P13 shipped**
- **Persona system**: default persona with ambiguity-handling clause
- **Memory**: pgvector semantic retrieval (`SmartMemoryAdvisor`), JSONB conversations, extraction pipeline
- **Multi-provider LLM**: OpenAI-compatible (SmartGate, OpenRouter, GitHub Copilot, direct OpenAI/Anthropic)
- **Routing**: `ModelRoutingAdvisor` + `TaskClassifier` — FAST / DEFAULT / ESCALATE feature-scored classification with explicit escalate flag override, routed model surfaced to CLI via span attributes — **P18 shipped**. Complexity unification (remove keyword dictionaries, merge with Embabel `TaskComplexity` pipeline) drafted as **P19**.
- **Reasoning model support**: `AgentService.extractResponseText` falls back to `reasoning_content` when `content` is empty (DeepSeek R1, arcee-trinity, o1 covered)

### CLI — `kukuvaia-cli`
- **Go 1.24 Bubbletea TUI** with Lipgloss + Glamour
- **Streaming SSE decoder** with per-block dispatch (no batch mode) — **P01.1 Stage C shipped**
- **Live activity tracker** (`activity/` package) with box-drawing, collapsed/expanded views, `ctrl+o` toggle, status symbols (● ✓ ✗ ⊘), routing badge for ESCALATE/FAST — **P01.1 Stage D shipped**
- **Inline snapshot per message**: activity tree frozen into each assistant message (Claude Code pattern)
- **Dynamic viewport height**: latest message + tracker always visible, scroll preserved across re-renders
- **Planning toolbar**: discovery/approval phases with keybindings
- Theme-driven styling from `kukuvaia-theme.yaml`

### Admin UI — `kukuvaia-admin`
- React 19 + Vite scaffold (provider/model management)

### Infrastructure
- PostgreSQL 17 with `pgvector` extension, Flyway migrations (V1–V12)
- Local ONNX embedding model (`all-MiniLM-L6-v2`, no external API)
- Credentials via `~/.kukuvaia/credentials.json` (chmod 600)

---

## Milestone 1: MVP Release (Open Source Launch)

**Goal:** Minimum viable product — installable, demo-able, publishable.
**Status:** Engine + CLI shipped; packaging + docs remaining.

### 1A. CLI Foundation `[✅ SHIPPED]`
- Go project with Bubbletea + Lipgloss + Glamour
- SSE streaming client (streaming cmd chain)
- TUI: input + chat viewport + OutputBlock rendering (8 types incl. `SpanEventBlock`)
- `~/.kukuvaia.yaml` config
- Theme → `styles_gen.go` via `go generate`

### 1B. Docker Compose `[Effort: S]`
- [ ] `docker-compose.yml` — engine + PostgreSQL + pgvector
- [ ] `Dockerfile` for `kukuvaia-engine` (multi-stage: build + runtime)
- [ ] Health check via existing `/actuator/health`
- [ ] One-liner: `docker compose up` → working agent

### 1C. README & Documentation `[Effort: M, partially shipped]`
- [x] Root `README.md` with prerequisites, setup, env vars, troubleshooting
- [x] `docs/architecture/` — chat-flow, agent-orchestration-decisions, etsl-integration, project-history, memory-architecture, embabel-integration
- [x] `docs/reference/` — SmartGate models + timeout
- [ ] GIF/video: install → add YAML tool → agent uses it (30 seconds)
- [ ] `CONTRIBUTING.md` — how to add tools, rules, skills
- [ ] `LICENSE` (Apache 2.0) — see `docs/plan/open-source-release.md`
- [ ] Example tools directory with 5+ ready-to-use tools

### 1D. Git Repository Setup `[Effort: S]`
- [x] Initialize git repo + `.gitignore`
- [x] First push to `github.com/bpawlakj/kukuvaia`
- [ ] GitHub Actions CI (build + test on PR)
- [ ] First tag: v0.1.0

### 1E. Server Hardening `[Effort: M]`
- [ ] Integration test: full flow (chat → tool call → response)
- [ ] Error handling in pipeline engine (Lua errors → readable messages)
- [ ] Graceful startup without LLM provider
- [ ] API documentation (OpenAPI/Swagger for `/api/*`)

---

## Milestone 2: Full CLI + Polish

### 2A. CLI Full TUI `[✅ SHIPPED]`
- OutputBlock renderers (Text, Table, Code, Progress, Plan, Verification, Metadata, **SpanEvent**)
- Session management (picker, new, delete)
- Keyboard shortcuts (Ctrl+C, Ctrl+L, Ctrl+O for activity toggle)
- Spinner during tool calls + activity tracker with verb animator
- `kukuvaia-theme.yaml` → `styles_gen.go`

### 2A.1. CLI Activity Tracker `[✅ SHIPPED]` — P01 + P01.1
- `activity/` package with span-tree state (Bubbletea Elm)
- Flat + nested multi-role rendering (supervisor + tools)
- `ctrl+o` collapse/expand
- Spinner verbs + token/time animator
- Theme-driven styling
- Inline snapshot per assistant message

### 2B. Authentication `[Effort: M]`
- [ ] OAuth device code flow (GitHub Copilot)
- [ ] JWT auth (SmartGate)
- [ ] API key auth for server endpoints (production mode)
- [x] Credential storage (`~/.kukuvaia/credentials.json`, chmod 600)

### 2C. Tool Ecosystem `[Effort: M]`
- [ ] 10+ built-in tools: web_search, git_diff, git_log, json_query, csv_parse, regex_extract, url_fetch, shell_run, env_read, timestamp
- [ ] Tool documentation generator (from `TOOL.md` frontmatter)
- [ ] Tool validation command (`kukuvaia tool validate ./my-tool/`)

### 2D. MCP Integration `[Effort: M]`
- [ ] Engine connects to CLI as MCP Client (local tools)
- [ ] Engine connects to ETSL MCP Server (domain tools) — see `docs/architecture/etsl-integration.md`
- [ ] MCP tool discovery at runtime (no restart)

---

## Milestone 3: Production & Community

### 3A. Production Readiness `[Effort: L]`
- [x] **P01 Observability (Stage A + B)** — OTel spans via `SpanEventEmitter`, Micrometer metrics, Prometheus scrape, `SpanEventBlock` SSE — shipped
- [ ] Helm chart for Kubernetes deployment
- [ ] Structured JSON logging (ELK/Loki compatible)
- [ ] Rate limiting per user/session
- [ ] Token budget per session with configurable limits

### 3B. Memory Architecture — `kukuvaia-memory` module `[Partially shipped]`
- [x] **Phase 4A**: Create `kukuvaia-memory` module + V2–V5 migrations (users, sessions, conversations, memories, plans)
- [x] **Phase 4B**: JSONB conversations + Session-User model
- [x] **Phase 4C**: pgvector semantic search + `SmartMemoryAdvisor` (top-K retrieval)
- [x] **Phase 4D**: Memory extraction pipeline (episodic/semantic/procedural facts post-session) + local ONNX embeddings
- [ ] **Phase 4E**: Embabel agent integration with memory module
- See: `docs/architecture/memory-architecture.md`

### 3B2. Embabel Agent Development `[Effort: L]`
- [ ] ResearchAgent (web search + document analysis with GOAP planning)
- [ ] ValidationAgent (multi-step validation workflows)
- [ ] Autonomy mode (LLM selects/composes agents from available actions)
- [ ] MCP Server export (agents as MCP tools for external systems)
- See: `docs/architecture/embabel-integration.md`

### 3B3. Remaining Agent Features `[Effort: M]`
- [ ] Intent-driven tool loading (load only relevant tools per request)
- [ ] Sub-agent delegation improvements (Embabel `RunSubagent` replacing `SubAgentFactory`)

### 3C. Web Frontend `[Effort: L]`
- [x] `kukuvaia-admin` React scaffold
- [ ] Full chat UI with same SSE API as CLI
- [ ] Span-event rendering for live activity
- [ ] Design tokens from `kukuvaia-theme.yaml` → CSS variables
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

Advanced agent capabilities — high-leverage once foundation is stable.

### 4A. Agent Reflection & Skill Library `[Effort: L]` — requires **P01 + P04**
- [ ] Skill Library schema + pgvector retrieval (semantic memory extension)
- [ ] `DreamingService` — nightly pattern extraction from plans/conversations
- [ ] Self-verification critic (Voyager pattern)
- [ ] Reflexion feedback loop — track success rate, archive failing skills
- See: `docs/plan/P12-agent-reflection.md`

### 4B. Obsidian Knowledge Base Integration `[Effort: S–M]` — requires **MCP Client wiring**
- [ ] MCP Client config for MCPVault / cyanheads / smart-connections
- [ ] Obsidian-aware rules in `~/.kukuvaia/rules/`
- See: `docs/plan/P11-obsidian-integration.md`

### 4C. Honest Agent `[Effort: L–XL]` — **P15 multi-pillar**
Differentiator against closed-source agents: transparent trust model.
- [ ] **Pillar 1 — Provenance Ledger**: `sources: List<Provenance>` on every `OutputBlock`, CLI colour-codes verified vs model-only
- [ ] **Pillar 2 — Red-Team Verification + Auto-Correction**: new `RED_TEAM` phase between DRAFTING and APPROVAL. Findings split into `AUTO_FIX` (mechanical defects — silent re-DRAFTING, 1-iteration cap, audit to `plan_revisions`), `RECOMMEND` (judgment calls — per-item checkboxes in APPROVAL panel) and `INFO` (audit trail only). `/plan --no-auto-fix` opt-out. Default supervisor-tier; advisor-tier only for `--strict`.
- [ ] **Pillar 3 — Empirical Calibration**: per-model per-domain accuracy tracking; force grounding in low-calibration domains
- [ ] **Pillar 4 — Pluggable Expert Marketplace**: `.kukuvaia/experts/*.yaml` with domain tools (geography-expert → OSM, legal-expert → corpus)
- See: `docs/plan/P15-honest-agent.md`

### 4D. Tiered Context `[Effort: M–L]` — **P14**
- [ ] `contextBudget` field on `PersonaSpec` (FULL / FOCUSED / MINIMAL)
- [ ] `ContextSelector` component with forwarding strategy (deterministic / LLM / hybrid)
- [ ] `DelegationTools.delegateToWorker` — symmetric to `consultExpert` (P15), routes simple subtasks to cheap worker tier
- [ ] Escalation protocol (`NEEDS_MORE_CONTEXT` signal)
- [ ] Integration diagram showing how `ModelRoutingAdvisor` + P14 + P15 compose
- See: `docs/plan/P14-tiered-context.md`

### 4E. Stepped Reasoning `[Effort: L–XL, feature-flagged]` — **P16**
Opt-in (off by default) phased rollout for CoT visibility and intervention.
- [ ] **Phase A**: parse `reasoning_content` into `llm:reasoning-step` spans (post-hoc, ~1–2 days)
- [ ] **Phase B**: `SteppedChatModel` wrapper consuming `.stream()`, emits steps live (~3–5 days)
- [ ] **Phase C**: anti-pattern table with pgvector matching, inject correction mid-generation (~3–4 weeks)
- Flag: `KUKUVAIA_STEPPED_REASONING` (master) + per-phase sub-flags
- See: `docs/plan/P16-stepped-reasoning.md`

### 4H. Routing Self-Tuning via Dreaming `[Effort: L, ~1 week]` — **P20** (builds on **P19** + **P12**)
Close the feedback loop: shadow-mode telemetry (P19) + implicit outcome signals (`/escalate` attribution, reword heuristic, thumbs feedback, token cost) feed a nightly `RoutingAuditTask` inside `DreamService`. Produces `DreamRecommendation`s that retune `complexity_mappings` + new `heuristic_weights` table, with optional auto-apply behind a confidence threshold and cooldown guard. De facto RouteLLM on our own telemetry — minus model training, plus Dreaming patterns.
- [ ] Phase A: V14 migration (`routing_decisions`, `heuristic_weights`), async per-turn insert in `ModelRoutingAdvisor`, `OutcomeTracker` (escalation attribution, Levenshtein reword, `ProviderAuditLog` join), CLI thumbs feedback (`ctrl+j`/`ctrl+k`)
- [ ] Phase B: `RoutingAuditTask` with deterministic decision rules (mismatch matrix → `TUNE_ROUTING` recs with YAML `suggested_action`), dashboard "Routing Intelligence" panel
- [ ] Phase C: auto-apply dispatcher with confidence threshold (0.85 default), cooldown per mapping key, max-changes-per-run guard, `/dream rollback <report-id>` command
- [ ] Silent-mode rollout: 2 weeks data collection → 2 weeks admin review → enable auto-apply
- See: `docs/plan/P20-routing-self-tuning.md`

### 4G. Complexity-Driven Routing `[Effort: M, ~1 week]` — **P19** (builds on **P18 ✅**)
Unify chat turn routing with Embabel action routing through a single `TaskComplexity` pipeline. Removes hardcoded keyword dictionaries from `TaskClassifier` + `IntentDetectionAdvisor` in favour of structural heuristics + LLM micro-classifier fallback, all resolving via the existing `complexity_mappings` table (V7).
- [ ] `ComplexityDetector` entrypoint + `StructuralHeuristics` (no word lists — length, `?`, code block, file path, planning mode, first-turn)
- [ ] `LlmComplexityClassifier` worker-tier fallback for ambiguous turns (confidence < 0.3), sha256 cache with 5 min TTL
- [ ] `/escalate` slash command replaces magical "think harder" phrases (one-turn hard override)
- [ ] Shadow-mode rollout (Phase 1) with `kukuvaia.routing.shadow_diff` metric → cutover (Phase 2) → delete `TaskClassifier` + keyword sets (Phase 3)
- [ ] Span attributes switch from `FAST/DEFAULT/ESCALATE` to `TaskComplexity` names + `routing.source` (heuristic/llm/flag) + `routing.confidence`
- [ ] Prometheus counter renamed to `kukuvaia.routing.complexity{complexity, source}` + new histogram `kukuvaia.routing.llm_fallback.duration`
- See: `docs/plan/P19-complexity-driven-routing.md`

### 4J. MCP Integration with sl-content (Teacher-Assistant Corpus Bridge) `[Effort: M, ~6 days]` — **P22**
Bridge kukuvaia to sl-content's corpus (books + 3 didactics layers in Weaviate) via MCP instead of reimplementing RAG. SL-content exposes 5 MCP tools (`retrieve_books`, `retrieve_didactics`, `list_books`, `get_book_meta`, `describe_catalogue`) on a new `/mcp` endpoint inside its existing FastAPI app. Kukuvaia's `spring-ai-starter-mcp-client` auto-discovers them at startup; a new `teacher` persona gates their exposure so default persona stays local-tools-only. New `/select-content` slash command stores `(country, subject, grade)` per session. First concrete realisation of P15 Pillar 4 (Expert Marketplace).
- [ ] Phase A: sl-content MCP server embedded in FastAPI (`backend/src/mcp_server/`), reuses existing retrieval services, API-key auth + rate limit
- [ ] Phase B: kukuvaia `spring.ai.mcp.client.sse.connections.sl-content` config, startup discovery log
- [ ] Phase C: `teacher` persona YAML, `SelectedContentService` + `/select-content` command, system-prompt injection
- [ ] Phase D: verify `ToolResultSanitizingAdvisor` covers MCP path, provenance TODO for P15 Pillar 1
- [ ] Phase E: `McpResultCache` (15-min TTL, sha256 key) + `kukuvaia.mcp.cache.hit/miss` metrics, sl-content rate limit (100 req/min/tenant)
- [ ] Phase F: `role:tool` spans with `kukuvaia.tool.source=mcp`, distinct CLI activity label, Prometheus histogram
- [ ] Phase G: `scripts/demo-teacher-flow.md` end-to-end — `/persona teacher` → `/select-content country=PL subject=math grade=7` → natural-language query → MCP retrieve → excerpts in response
- See: `docs/plan/P22-mcp-sl-content-integration.md`

### 4I. Plan Registry & Composition `[Effort: M–L, ~1 week]` — **P21** (extends **P13 ✅**)
Elevate plans from session-local state to first-class objects with persistence, interactive browsing, resume-after-restart, and composition. Users invoke `/plans` or ask "jakie mam plany?" → arrow-navigable picker → resume/combine/abandon. Combining parent plans creates a new plan with merged `DiscoveryFacts` + `plan_links` (relations: `combines` / `derived_from` / `follows` / `alternative_to`).
- [ ] Phase A: V16 migration (extend `plans` with `name`, `phase`, `discovery_facts`; new `plan_links` table), `PlansRepository` extensions, `PlanController` with user-scoped auth, `PlanListBlock` OutputBlock
- [ ] Phase B: `PlanPicker` CLI component (mirror `SessionPicker`), `/plans` slash command, `ctrl+p` rewired to open picker (was `/plan cancel`), auto-open on `PlanListBlock` arrival
- [ ] Phase C: `PlanningModeService.resumeFromDb(planId)` (rehydrates phase + facts from DB), `combinePlans(parents, newTask)` with naive fact merge + dedup, phase persistence in existing transitions, `/plan resume|combine|new --parent` commands
- [ ] Phase D: `@Tool list_plans / resume_plan / combine_plans` for natural-language flow, persona system prompt registration
- See: `docs/plan/P21-plan-registry-composition.md`

### 4F. Commitments & Pending Tasks Memory `[Effort: M, ~1 week]` — **P17**
Lightweight task/reminder store for the gap between `/plan` (heavyweight, structured) and casual user mentions ("remind me to rebook the flight").
- [ ] Flyway V13 — new `kukuvaia.commitments` table (UUID, user/session scope, status, due_hint, relevance_score)
- [ ] `CommitmentRepository` + JDBC tests
- [ ] `@Tool` surface: `addCommitment`, `updateCommitmentStatus`, `listCommitments`
- [ ] Slash commands: `/todo add|list|done|drop`
- [ ] Extraction integration via `MemoryExtractionService` (LLM + keyword fallback)
- [ ] `SessionContextAdvisor` surfaces up to 5 open commitments (proactive mention)
- [ ] Decay pass piggybacks on existing `MemoryConsolidationService`
- Trigger: activate when users report forgotten casual mentions OR when `/plan` feels heavyweight for one-liners
- See: `docs/plan/P17-commitments-memory.md`

---

## Plan → Milestone Mapping

Quick reference — which plan document belongs to which milestone:

| Plan | Milestone | Status | Depends on |
|------|-----------|--------|------------|
| **P01 Observability (incl. SpanEventBlock)** | 3A | **✅ SHIPPED** | none |
| **P01.1 CLI Activity Tracker** | 2A.1 | **✅ SHIPPED** | P01 SpanEventBlock |
| P02 Structured Output | 1A/2A | Draft | none |
| P03 Model Fallback Chain | 3A | Draft | P01 |
| P04 Conversation Summarization | 3B | Draft | kukuvaia-memory (✅) |
| P05 Eval Pipeline | 3A | Draft | P01 (✅) |
| P06 Content Moderation | 3A | Draft | none |
| P07 Cost Tracking | 3A | Draft | P01 (✅) |
| P08 Prompt Cache Optimization | 3A | Draft | P01 (✅) |
| P09 Human-in-the-Loop | 3B | Draft | P02 |
| P10 Embabel GOAP Agents | 3B2 | Draft | Embabel wired (✅) |
| P11 Obsidian Integration | 4B | Draft | MCP Client |
| P12 Agent Reflection / Dreaming | 4A | Draft | P01 (✅) + P04 |
| **P13 Planning Discovery State** | (spans milestones) | **✅ SHIPPED** | none |
| **P14 Tiered Context** | 4D | Draft | P15 Pillar 4 recommended |
| **P15 Honest Agent (4 pillars)** | 4C | Draft | P01 (✅), `kukuvaia-memory` (✅) |
| **P16 Stepped Reasoning** | 4E | Draft (flagged) | P01 (✅); P15 Pillar 3 for Phase C |
| **P17 Commitments Memory** | 4F | Draft | `kukuvaia-memory` (✅); piggybacks on `MemoryExtractionService` + `MemoryConsolidationService` |
| **P18 Intelligent Task Routing** | 4G | **✅ SHIPPED** | none |
| **P19 Complexity-Driven Routing** | 4G | Draft | P18 (✅); `complexity_mappings` table V7 (✅) |
| **P20 Routing Self-Tuning via Dreaming** | 4H | Draft | P19 Phase 1 (shadow mode telemetry); P12 / `DreamService` + V9 tables (✅) |
| **P21 Plan Registry & Composition** | 4I | Draft | P13 (✅); `plans` table V4 (✅); `SessionPicker` (✅); `@Tool` (✅) |
| **P22 MCP Integration with sl-content** | 4J | Draft | `spring-ai-starter-mcp-client` (✅); `ToolResultSanitizingAdvisor` (✅); sl-content retrieval services (✅, external repo) |
| `open-source-release.md` | 1C / 1D | Draft | LICENSE + CONTRIBUTING |

**Critical path for MVP launch**: `1B` (Docker) + `1C` (README/LICENSE) + `1D` (CI) + `1E` (hardening). Everything P0x is infrastructure that already exists or can ship in parallel.

---

## Architecture Principles

1. **Tools are YAML, not code** — users don't need Java/Go to extend the agent
2. **Hot reload everything** — tools, rules, skills — no restart needed
3. **Provider agnostic** — any OpenAI-compatible LLM (SmartGate, Claude, GPT, OpenRouter, local)
4. **MCP native** — standard protocol for tool integration (both client and server)
5. **Self-hosted first** — runs on your infra, your data stays with you
6. **Security by design** — sandbox (Lua), path traversal prevention, parameterized SQL, STRIDE reviewed
7. **Composition over inheritance** — never subclass Spring AI classes; wrap via beans with `@ConditionalOnProperty` where optional
8. **Feature-flag purity** — opt-in features (P16) behave bit-identically to pre-feature state when their flag is off
9. **Observability from day one** — every new subsystem exposes Micrometer metrics and/or `SpanEventBlock` spans

---

**Effort Scale**: `S` 2–3 days | `M` ~1 week | `L` 2+ weeks | `XL` 1+ month

*Last updated: 2026-04-20 (4J / P22 draft — MCP bridge to sl-content corpus, first realisation of P15 Pillar 4; 4I / P21 draft — plan registry & composition; 4H / P20 draft — routing self-tuning; P15 Pillar 2 expanded; 4G / P19 Phase 1 shipped)*

# P22 — MCP Integration with sl-content (Teacher-Assistant Corpus Bridge)

**Status:** Draft — ready to implement pending 4 design decisions (§"Open questions").
**Created:** 2026-04-20
**Depends on:**
- Kukuvaia: `spring-ai-starter-mcp-client` already on classpath (✅). `ToolResultSanitizingAdvisor` (✅).
- SL-content (`/home/bartek/Projects/sl-content/teacher-assistant`): existing retrieval services in `backend/src/llm/retrieval/` (✅) — `run_books_retrieval`, `run_common_didactics`, `run_country_general_didactics`, `run_country_subject_didactics`.
- Network reachability between the two systems (local Docker Compose or VPC/shared network in cloud).

**Scope:** Make sl-content's retrieval pipeline available to kukuvaia through the Model Context Protocol. Kukuvaia becomes a thin consumer — it doesn't re-implement book/didactics RAG, it calls sl-content's tools. Same principle as P15 Pillar 4 (expert marketplace): domain-specific knowledge lives close to the domain, kukuvaia orchestrates.

## Problem

Kukuvaia today has two retrieval surfaces:
1. **Memory** (`SmartMemoryAdvisor` + pgvector) — user-scoped short-term context.
2. **@Tool annotations** — local Java-defined tools only.

No access to structured domain corpora. For education-related sessions this means the agent either:
- Makes up facts about textbooks it has never seen.
- Asks the user to paste the textbook in every turn.
- Fails on queries like "find exercises about linear equations in the Polish grade-7 math book".

Meanwhile sl-content already owns:
- ~N books × metadata × cover images (S3).
- 3 layers of didactics corpora in Weaviate (common / country-general / country-subject).
- `RetrievalType` enum + working retrieval pipeline tested in the TA product.

Duplicating any of that in kukuvaia is pure waste. MCP is the clean seam.

## Goal

When the kukuvaia agent enters a conversation touching education content, it can:

1. Call `retrieve_books(query, country, subject, grade)` through MCP and get back a structured list of book excerpts with provenance.
2. Call `retrieve_didactics(kind, query, country?, subject?)` for didactics at the right scope.
3. Render the sources inline in the CLI (via the existing OutputBlock pipeline + eventual P15 Pillar 1 Provenance).
4. Do all this without a single `import` from sl-content — the seam is MCP protocol + JSON schema only.

No shared code, no shared DB, no shared deployment. Two services, one protocol.

## Non-goals

- **Copy sl-content's code into kukuvaia.** The whole point is that we don't.
- **Replace sl-content's internal agent with kukuvaia.** sl-content keeps its own agent for the TA product; kukuvaia is a separate tenant consuming the same retrieval.
- **Write to sl-content.** MCP tools are read-only at first — no mutations, no user-authored content. Write-back is a later plan.
- **Full parity with TA's generators** (exercise/test/grading). Those are P22 non-goals — generator-as-MCP-tool is a separate future plan (P26?).
- **Expose sl-content's MCP server to third parties.** Scoped to kukuvaia and the TA product for now.

## Architecture

```
┌─ kukuvaia-engine ──────────────────────────────────────┐
│                                                        │
│   AgentService (Spring AI ChatClient)                  │
│     └─► ToolCallAdvisor                                │
│           ├─► Local @Tool (MemoryTools, PlanningTools) │
│           └─► MCP-discovered tools     ◄────────┐      │
│                   (auto-registered at startup)  │      │
│                                                 │      │
│   McpClientConfig                               │      │
│     └─► spring.ai.mcp.client.sse.connections:   │      │
│          sl-content:                            │      │
│            url: https://sl-content/mcp          │      │
│            headers:                             │      │
│              X-API-Key: ${SL_CONTENT_MCP_KEY}   │      │
│              X-Tenant: kukuvaia                 │      │
│                                                 │      │
└─────────────────────────────────────────────────┼──────┘
                                                  │
                            MCP over HTTP/SSE     │
                            (JSON-RPC 2.0 frames) │
                                                  │
┌─ sl-content ────────────────────────────────────▼──────┐
│                                                        │
│   FastAPI app (existing TA backend)                    │
│     └─► New POST /mcp endpoint (SSE)                   │
│           MCP server (Python `mcp` SDK)                │
│             ├─► tools/list   — 5 tools                 │
│             ├─► tools/call   — dispatch by name        │
│             │                                          │
│             ├─► retrieve_books(…)                      │
│             │      → src/llm/retrieval/books.py        │
│             │                                          │
│             ├─► retrieve_didactics(kind, …)            │
│             │      → src/llm/retrieval/didactics.py    │
│             │                                          │
│             ├─► list_books(filters)                    │
│             │      → DynamoDB/S3 metadata              │
│             │                                          │
│             └─► get_book_meta(book_id)                 │
│                                                        │
│   Auth middleware: X-API-Key + per-tenant rate limit   │
│   Observability: existing CloudWatch logs + request id │
└────────────────────────────────────────────────────────┘
```

## Tool contract (what sl-content exposes)

Five tools total. Names intentionally verb-first + noun to match MCP conventions.

### 1. `retrieve_books`

```json
{
  "name": "retrieve_books",
  "description": "Semantic search over the teacher-assistant book corpus. Returns ranked excerpts with book metadata.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query":    { "type": "string", "description": "Natural-language query." },
      "country":  { "type": "string", "enum": ["PL","DE","EN","ES"], "description": "ISO country code." },
      "subject":  { "type": "string", "description": "Subject slug (math, physics, biology, …)." },
      "grade":    { "type": "integer", "minimum": 1, "maximum": 12 },
      "limit":    { "type": "integer", "default": 5, "maximum": 10 }
    },
    "required": ["query","country"]
  }
}
```

Response shape: `{ "excerpts": [{ "bookId", "bookTitle", "chapter", "text", "score" }], "totalFound": N }`.

### 2. `retrieve_didactics`

```json
{
  "name": "retrieve_didactics",
  "description": "Retrieve didactics guidance. Scope: 'common' (cross-country), 'country_general', or 'country_subject'.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "kind":    { "type": "string", "enum": ["common","country_general","country_subject"] },
      "query":   { "type": "string" },
      "country": { "type": "string" },
      "subject": { "type": "string" },
      "limit":   { "type": "integer", "default": 3, "maximum": 8 }
    },
    "required": ["kind","query"]
  }
}
```

Response: `{ "entries": [{ "source", "text", "score" }], "kind": "..." }`.

### 3. `list_books`

```json
{
  "name": "list_books",
  "description": "List available books filtered by country/subject/grade. Discovery helper for the agent.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "country": { "type": "string" },
      "subject": { "type": "string" },
      "grade":   { "type": "integer" },
      "limit":   { "type": "integer", "default": 20 }
    }
  }
}
```

### 4. `get_book_meta`

```json
{
  "name": "get_book_meta",
  "description": "Full metadata for a single book — title, chapters, publication info, cover URL.",
  "inputSchema": {
    "type": "object",
    "properties": { "bookId": { "type": "string" } },
    "required": ["bookId"]
  }
}
```

### 5. `describe_catalogue`

```json
{
  "name": "describe_catalogue",
  "description": "One-shot overview of available content categories — agent uses this on first turn to learn what's available."
}
```

Returns: `{ "countries": [...], "subjects": [...], "totalBooks": N, "didacticsLayers": [...] }`.

This last one is non-obvious but valuable — it replaces the "agent hallucinates subjects that don't exist" failure mode.

## Auth + tenancy

**Simplest acceptable model for MVP:** shared API key + explicit tenancy parameter on every tool call.

```
POST /mcp
Headers:
  X-API-Key: <rotated secret stored in kukuvaia credentials file>
  X-Tenant: kukuvaia
```

Tool arguments carry user-scoped filters (`country`, `subject`, `grade`). Sl-content enforces filter validity, kukuvaia is responsible for passing correct values from its session context.

**Why not pass user tokens through?**
- Sl-content's Auth0/Cognito flow assumes a TA-product user session — kukuvaia's users don't have those.
- Federating identity is a separate plan. MVP uses service-to-service auth.

**Rotation:** key rotation without redeploy — kukuvaia reads `${SL_CONTENT_MCP_KEY}` from env, sl-content validates against hot-reloadable config.

**Audit:** every tool call on sl-content side logs `(tenant, tool, args_hash, user_id_claim?, duration, result_size)`. Persisted to CloudWatch (existing TA infrastructure).

## Phased rollout

### Phase A — SL-content MCP server (1.5 days traditional / ~2h AI-paired)

Location: new `sl-content/teacher-assistant/backend/src/mcp_server/` module inside the existing FastAPI app. Reuses retrieval services directly — no code duplication.

Deliverables:
- `pyproject.toml` dependency `mcp >= 1.0` (Python SDK).
- `src/mcp_server/tools.py` — implementations wrapping `src/llm/retrieval/*`.
- `src/mcp_server/server.py` — MCP server using `mcp.server.fastapi` integration.
- Mount on existing FastAPI: `POST /mcp` with SSE response.
- `src/mcp_server/auth.py` — API-key middleware with rate limit.
- Unit tests: each tool returns expected structure for a golden query.
- Integration test: start FastAPI, hit `/mcp` with `tools/list`, assert 5 tools.

### Phase B — Kukuvaia MCP client (0.5 day / 30 min AI-paired)

Already have `spring-ai-starter-mcp-client`. Configure connection in `application.yaml`:

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            sl-content:
              url: ${SL_CONTENT_MCP_URL:http://localhost:8000/mcp}
              sse-endpoint: /sse
              request-timeout: 30s
              custom-headers:
                X-API-Key: ${SL_CONTENT_MCP_KEY}
                X-Tenant: kukuvaia
```

Tool discovery is automatic — Spring AI MCP client calls `tools/list` at startup and registers each as a `ToolCallback`. They appear in the same callback list that `ToolCallAdvisor` iterates over, side-by-side with `@Tool`-annotated Java methods.

Acceptance: start kukuvaia with sl-content running, check `/actuator/beans` — MCP tools show up. Log at `INFO` level on startup: `MCP client: discovered 5 tools from sl-content`.

### Phase C — Agent integration & persona wiring (1 day / 50 min AI-paired)

Two UX questions to answer:

1. **When should the agent reach for these tools?** — Two options:
   - (a) Always expose — rely on tool descriptions + LLM judgement. Simpler. May over-call on trivial chats.
   - (b) Persona-gated — new `teacher` / `student` / `editorial` personas that include the MCP tools; other personas don't see them. Cleaner, matches TA's `AssistantType` model.

   **Proposed:** (b) — ship a `teacher` persona YAML in `.kukuvaia/personas/teacher.yaml` that pulls in the MCP tools. Default persona stays local-tools-only.

2. **Where does `country` / `subject` come from?** — Three options:
   - User asks per-turn ("for PL grade 7 math, find...").
   - Session-level selection via `/select-content country=PL subject=math grade=7` slash command (stored in session state).
   - Profile-level default in `.kukuvaia/personas/teacher.yaml`.

   **Proposed:** combined — profile sets defaults, slash command overrides per session, per-turn phrase always wins. Explicit `SelectedContentService` extracts and stores these, `SessionContextAdvisor` surfaces them to the system prompt.

Deliverables:
- `persons/teacher.yaml` template + loader support.
- `SelectedContentService` (new, Java) — per-session `(country, subject, grade)` state with transient override.
- `/select-content` slash command (`SelectContentCommand implements SlashCommand`).
- System prompt injection in `PersonaService` — when `teacher` persona active, include a line like "The user is teaching in {country}, subject {subject}, grade {grade}. Use MCP retrieval tools when relevant."
- Tests: agent with `teacher` persona calls `retrieve_books` on a relevant query; default persona does not.

### Phase D — Provenance + sanitisation (0.5 day / 30 min AI-paired)

MCP responses carry untrusted external content — must run through the existing sanitisation chain.

Deliverables:
- `ToolResultSanitizingAdvisor` already wraps all tool results with prompt-injection boundary markers. Verify it fires for MCP tools too (they go through the same advisor chain). Add test fixture: MCP tool returns a string containing `"ignore previous instructions"` — assert sanitiser wraps it.
- Attach `Provenance(TOOL, "retrieve_books:<bookId>", VERIFIED)` to any `OutputBlock` derived from MCP tool results. Uses the P15 Pillar 1 provenance scaffolding (NOT shipped yet — so Phase D leaves a `TODO` marker pointing at P15 Pillar 1).
- CLI rendering hint: MCP-sourced text blocks get a small "🔗 sl-content" badge. Requires extending `PlanBlock`/`TextBlock` rendering once provenance ships — for now, log-only.

### Phase E — Caching + rate limits (0.5 day / 30 min AI-paired)

MCP retrieval can be slow (Weaviate + LLM embed). Cache on the kukuvaia side to avoid re-asking the same question mid-conversation.

Deliverables:
- `McpResultCache` service — `ConcurrentHashMap<sha256(tool+args), CachedResult>` with 15-minute TTL, bounded at 500 entries. Same pattern as `LlmComplexityClassifier` cache (P19).
- Wrap MCP tool invocations with a cache check in a dedicated `McpCachingToolCallback` decorator — OR, if Spring AI MCP client doesn't expose that hook cleanly, a pre-advisor that intercepts `tools/call` and short-circuits on cache hit.
- Config flag: `kukuvaia.mcp.cache.enabled: true`, `kukuvaia.mcp.cache.ttl-minutes: 15`.
- Metrics: `kukuvaia.mcp.cache.hit` / `miss` counters.
- SL-content side: add rate limit on `/mcp` per tenant header — reuse existing rate-limit middleware if present, else add simple sliding window (100 req/min default).

### Phase F — Observability (0.25 day / 15 min AI-paired)

MCP tool calls should appear in the CLI activity tracker like local tools do. They mostly already will (span emitter runs in `ToolCallMetrics` AOP aspect on any `ToolCallback`) — verify and polish.

Deliverables:
- Confirm `role:tool` span fires for MCP tool invocations with `kukuvaia.tool.source=mcp` attribute. Patch `ToolCallMetrics` if the MCP path bypasses the aspect.
- CLI activity tracker: show MCP-sourced tools with a distinct icon or prefix (e.g. `tool/mcp:retrieve_books` vs `tool:memory_search`). Change in `activity/` package, one line style tweak.
- Prometheus: new histogram `kukuvaia.mcp.tool.duration_seconds{tool, tenant, outcome}`.

### Phase G — Teacher persona E2E demo (0.5 day / 30 min AI-paired)

Concrete demo-worthy flow the whole plan is justified by. This phase is the acceptance for the whole plan, not new feature code beyond a persona YAML + demo script.

Deliverables:
- `.kukuvaia/personas/teacher.yaml` — production copy with system prompt, MCP tools list, default country/subject.
- `scripts/demo-teacher-flow.md` — step-by-step: start both services, `/persona teacher`, `/select-content country=PL subject=math grade=7`, ask "pokaż mi zadania o liniowych równaniach z podręcznika" → agent calls `retrieve_books`, returns excerpts with book titles + chapter + text. All visible in activity tracker.
- Screenshot or GIF in `docs/demos/`.

## Open questions — need decision before implementation

1. **Transport — HTTP/SSE vs stdio vs streamable-http?** MCP supports all three. stdio is great for local dev with docker-compose (single process pair). HTTP/SSE is prod-friendly. Streamable-HTTP (newer) is best-of-both but less mature in the Python SDK.
   **Proposed:** HTTP/SSE for MVP. Add stdio bridge later if local dev UX demands it.

2. **MCP server hosting — same FastAPI process or separate?** Embedding keeps retrieval services in-process; separating gives isolation + independent deploy.
   **Proposed:** embed in existing FastAPI app. One deployment, reuses auth/config/logging. Spin out later if multi-tenant load becomes an issue.

3. **Auth — shared API key or per-tenant JWT?** API key is trivial and matches service-to-service reality. JWT gives expiry + claims richness but adds a rotation dance.
   **Proposed:** shared API key MVP, plan JWT upgrade for Phase H (new plan) when we onboard a third tenant.

4. **Content scoping fallback — ask user or refuse?** If agent tries `retrieve_books` without a country set, what happens?
   **Proposed:** sl-content MCP server returns a structured error `{error: "missing_required_parameter", required: ["country"]}`. Kukuvaia agent sees the error and asks the user or falls back to `describe_catalogue` to bootstrap.

## Acceptance criteria (whole plan)

- [ ] SL-content `/mcp` endpoint responds to `tools/list` with exactly 5 tools and correct schemas
- [ ] `X-API-Key` validation works — missing/wrong key returns 401
- [ ] Kukuvaia startup discovers all 5 tools and logs them
- [ ] `teacher` persona activates MCP tools, default persona does not
- [ ] `/select-content` command stores country/subject/grade in session; surfaced in next system prompt
- [ ] Agent in `teacher` persona, asked "znajdź w PL mat grade 7 zadania o równaniach" → calls `retrieve_books` with correct args → returns excerpts
- [ ] Invalid args return structured error, agent surfaces a user-friendly message
- [ ] MCP tool results pass through `ToolResultSanitizingAdvisor`
- [ ] Cache returns same result within 15 min for identical args
- [ ] Activity tracker in CLI shows MCP tool calls with distinct label
- [ ] Prometheus histogram populated for MCP tool durations
- [ ] SL-content rate limit returns 429 above threshold (100 req/min default)
- [ ] Demo script in `scripts/demo-teacher-flow.md` runs green end-to-end

## Tests

- `sl-content` pytest: tool schema validation, each tool returns correct shape on golden input, auth middleware, rate limit
- `kukuvaia` JUnit: `McpResultCache` (TTL + eviction), `SelectedContentService` (override precedence), persona activation registers MCP tools
- Integration (kukuvaia-side, docker-compose): start sl-content MCP server + kukuvaia, `/persona teacher`, send test query, assert MCP tool call happened (span event) and result text contains expected book id prefix
- Chaos: sl-content down → kukuvaia agent falls back gracefully (error message, not crash)

## Security

- MCP server runs inside existing sl-content FastAPI — all existing hardening (input validation, logging, timeouts) applies
- API key stored encrypted in kukuvaia credentials file (chmod 600 — existing `CredentialsFileGuard`)
- Rate limit per tenant — blunt force protection
- Tool responses sanitised via existing `ToolResultSanitizingAdvisor` (prompt-injection defence)
- Response size capped at 32 kB per tool call (MCP server truncates with explicit marker); prevents context bombs
- No PII in tool-call logs — args hash, not args, persisted long-term
- Schema validation on BOTH sides — sl-content rejects bad tool calls with `400`, kukuvaia validates response shape before passing to advisor chain

## Effort

| Task | Traditional | AI-paired |
|---|---|---|
| Phase A — MCP server in sl-content | 1.5 days | 2 h |
| Phase B — kukuvaia MCP client config | 0.5 day | 30 min |
| Phase C — persona + content selection | 1 day | 50 min |
| Phase D — sanitisation verify + provenance TODO | 0.5 day | 30 min |
| Phase E — cache + rate limit | 0.5 day | 30 min |
| Phase F — observability polish | 0.25 day | 15 min |
| Phase G — demo + persona yaml | 0.5 day | 30 min |
| Tests (python + java + integration) | 1.5 days | 90 min |
| **Total** | **~6.25 days** | **~5.5 h** |

## Dependencies

- Kukuvaia `spring-ai-starter-mcp-client` — shipped
- SL-content `mcp` Python SDK — new dependency (stable, Anthropic-maintained)
- Both services need network reachability — assumed via docker-compose in dev, VPC in cloud
- For SSE transport: both sides need HTTP/2 or long-lived connection support (default FastAPI + Spring Boot OK)
- `CredentialsFileGuard` for API key storage — shipped

## Relationship to other plans

- **P15 Pillar 4 (Expert Marketplace)** — this plan is the first concrete realisation. `teacher` persona via sl-content MCP is effectively the "geography-expert / legal-expert" pattern, done for real. P15 Pillar 4's YAML expert loader should later be able to read an `mcp-endpoint` field pointing at remote MCP servers — this plan's `.kukuvaia/personas/teacher.yaml` is the prototype for that schema.
- **P15 Pillar 1 (Provenance Ledger)** — MCP tool results should carry `Provenance(TOOL, "sl-content:retrieve_books:<bookId>", VERIFIED)`. Phase D leaves a TODO until Pillar 1 ships.
- **P14 (Tiered Context)** — MCP excerpts are large. Context budget will often force trimming. `ContextSelector` should know that MCP-sourced blocks are first-class "evidence" to preserve over less-critical context.
- **P17 (Commitments)** — irrelevant, different surface
- **P19 (Complexity Routing)** — retrieval-heavy turns might benefit from `SYNTHESIS` complexity. Log correlation: turns with ≥ 1 MCP tool call trend toward SYNTHESIS? P20 can surface this pattern.
- **P20 (Routing Self-Tuning)** — MCP tool usage becomes a telemetry signal. "ANALYSIS complexity + MCP retrieval" is a distinct behaviour class worth tracking.
- **P21 (Plan Registry)** — orthogonal. A plan might include steps that reference MCP-sourced books (by id), but the plan registry doesn't need to understand MCP.

## Risks

- **Weaviate query latency** — TA's current retrieval is ~500ms–2s. Adding MCP round-trip adds ~50–100ms. Agent may time out on slow queries. Mitigation: cache (Phase E) + MCP `request-timeout: 30s` ceiling + cancellation propagation.
- **Schema drift** — sl-content iterating on tool schemas breaks kukuvaia. Mitigation: version tool names (`retrieve_books_v1` when breaking) + kukuvaia tolerates missing optional fields via Jackson.
- **Auth key leak** — shared key means any leaked kukuvaia deployment gets corpus access. Mitigation: rotate quarterly, audit log + anomaly detection on sl-content side.
- **Large response blowing context** — 32 kB cap (§Security) + summarisation advisor (future P14) mitigate.
- **Kukuvaia agent over-retrieves** — LLM may call `retrieve_books` on every trivial turn. Mitigation: persona-gated exposure (Phase C) + cache (Phase E) + P20 routing insights later.

## Implementation readiness

**Can it be implemented now? — Yes, with 4 open questions to answer first (above).** All other dependencies are in place. The biggest unknown is rigour of MCP Python SDK on FastAPI — worth a quick spike (30 min) before committing to Phase A to validate that `mcp.server.fastapi` mounts cleanly. If not, fall back to standalone MCP server process behind sl-content's existing ALB.

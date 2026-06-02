# P22 — MCP Integration with sl-content (Teacher-Assistant Corpus Bridge)

**Status:** In progress — Phases A + B shipped (DB-backed rewrite). Phases C–G pending.
**Created:** 2026-04-20
**Depends on:**
- Kukuvaia: `spring-ai-starter-mcp-client` already on classpath (✅). `ToolResultSanitizingAdvisor` (✅).
- SL-content (`/home/bartek/Projects/sl-content/teacher-assistant`): existing retrieval services in `backend/src/llm/retrieval/` (✅) — `run_books_retrieval`, `run_common_didactics`, `run_country_general_didactics`, `run_country_subject_didactics`.
- Network reachability between the two systems (local Docker Compose or VPC/shared network in cloud).

**Scope:** Make sl-content's retrieval pipeline available to kukuvaia through the Model Context Protocol. Kukuvaia becomes a thin consumer — it doesn't re-implement book/didactics RAG, it calls sl-content's tools. Same principle as P15 Pillar 4 (expert marketplace): domain-specific knowledge lives close to the domain, kukuvaia orchestrates.

## Implementation log (condensed)

| Phase | Status | Commit-ready summary |
|---|---|---|
| A — Remote MCP server | **Shipped** in `sl-content-engine` (not in `teacher-assistant` — corpus-level surface, not product-level). HTTP/SSE under `/mcp`, API-key + tenant middleware, per-tenant rate limit, opt-in via `MCP_HTTP_ENABLED`. Reuses existing 9 tools registered in `mcp_tools.py`. |
| B — Kukuvaia MCP client | **Shipped, DB-backed**. Connection registry moved from YAML to `kukuvaia.mcp_connections` table (V17). `DbMcpSseClientConnectionDetails` overrides Spring AI autoconfig; `PerConnectionHeaderCustomizer` resolves `env:VAR_NAME` refs at request time. Admin CRUD exposed via `/api/admin/mcp/connections`. |
| C — Agent integration & persona wiring | **Pending** — requires a generic persona `toolAllowList` + session-params primitives (see revised §"Phase C" below). The domain-specific pieces (`teacher` persona, `/select-content` command, education-specific fields) ship as user-authored `.kukuvaia/` extensions, not engine code. |
| D — Provenance + sanitisation | Pending (blocked on P15 Pillar 1 for full provenance; sanitisation path exists). |
| E — Caching + rate limits | Pending on kukuvaia side; server-side rate limit shipped in Phase A. |
| F — Observability polish | Pending. |
| G — Teacher persona E2E demo | Pending — depends on Phase C generic primitives + user-authored `.kukuvaia/personas/teacher.yaml` example in `docs/`. |

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

### Phase A — SL-content MCP server (shipped)

**Final location change:** originally drafted to sit inside the
`teacher-assistant` backend (FastAPI); shipped instead in
`sl-content-engine` which is the actual corpus + classification platform
(teacher-assistant is one downstream consumer). This also aligns with
the product positioning — MCP surface belongs with the corpus, not
with a specific product skin.

**Delivered:**

- Config fields in `sl_content_agent.config.Settings` —
  `MCP_HTTP_ENABLED` (default false), `MCP_HTTP_API_KEYS`
  (`tenant:secret,...`), `MCP_HTTP_RATE_LIMIT_PER_MINUTE` (default 100),
  `MCP_HTTP_RESPONSE_MAX_CHARS` (reserved for future per-tool truncation).
- `sl_content_agent/mcp_auth.py` — `parse_api_keys()` format parser,
  `SlidingWindowRateLimiter` (per-tenant, 60 s window, O(1) hot path),
  `APIKeyMiddleware` (ASGI) with `hmac.compare_digest` verification,
  fail-closed on empty key set.
- `sl_content_agent/mcp_http.py` — `build_mcp_routes()` returns Starlette
  `Route /sse` + `Mount /messages/` using the MCP SDK's
  `SseServerTransport`; `create_mcp_subapp()` wraps with
  `APIKeyMiddleware`; `create_query_encoder()` warm-loads BGE-M3 with a
  graceful fallback if the embedder is missing.
- Integration in existing `src/sl_content_agent/api/app.py` lifespan:
  gated by `MCP_HTTP_ENABLED`, mounts the sub-app at `/mcp`, reuses the
  dashboard API's asyncpg pool + motor client + `register_vector`.
- Reuses existing `register_tools` from `mcp_tools.py` — 9 tools
  discovered by kukuvaia (matches expectations): `search`, `search_text`,
  `get_item`, `find_similar`, `list_outlines`, `get_outline`,
  `search_outlines`, `list_ccl_books`, `get_counts`.
- Tests: 19 unit (`test_mcp_auth.py` — key parsing edge cases, rate-
  limiter tenant isolation, constant-time verify, empty-key-set rejects)
  + 5 integration (`test_mcp_http_integration.py` via
  `StarletteTestClient` — 401 on missing/wrong key, 401 on tenant
  mismatch, 429 after threshold, tenant-isolated limits).

**Opt-in deployment:**

```bash
# .env
MCP_HTTP_ENABLED=true
MCP_HTTP_API_KEYS=kukuvaia:$(openssl rand -hex 32)
# Then
uv run sl-api
```

### Phase B — Kukuvaia MCP client (shipped, DB-backed)

**Major rewrite during implementation:** originally drafted as YAML-only
config under `spring.ai.mcp.client.sse.connections.*` with per-connection
custom headers. Final shipped form is **DB-backed** so the admin
dashboard can add, rotate and disable remote MCP servers without a
redeploy.

**Driver:** Spring AI 1.1.0's `SseParameters` record is `(url, sseEndpoint)`
only — no headers field. Combined with the user's preference for
generic-platform behaviour (no hardcoded server names, no per-server env
var prefixes) and the updated credentials standard (secrets from env
only, not files), the registry landed in Postgres with header values
using an `env:VAR_NAME` convention for rotation.

**Delivered:**

- **V17 migration** — `kukuvaia.mcp_connections` (`id`, `name` UNIQUE,
  `url`, `sse_endpoint`, `headers` JSONB, `enabled`, `description`,
  timestamps).
- `McpConnection` record + `McpConnectionsRepository` (CRUD, upsert via
  admin API).
- `McpConnectionsCache` — startup snapshot of enabled rows, thread-safe,
  `refresh()` on admin writes, URL-prefix lookup for per-request header
  dispatch.
- `DbMcpSseClientConnectionDetails` — implements Spring AI's
  `McpSseClientConnectionDetails` hook; autoconfig picks this up
  instead of its properties-backed default, so the YAML
  `spring.ai.mcp.client.sse.connections.*` block is intentionally
  **absent** from `application.yaml`.
- `McpClientConfig` — provides the connection-details bean plus a
  `McpSyncHttpClientRequestCustomizer` that resolves `env:VAR_NAME`
  header values via `System.getenv` at request time. Missing env vars
  drop the header with a warning (no crash).
- `McpConnectionsController` — `GET|POST|PUT|DELETE /api/admin/mcp/connections`
  + `POST /api/admin/mcp/connections/refresh` for cache refresh.
- Admin dashboard integration point — React scaffold can CRUD via REST;
  UI work tracked separately.
- Master toggle `KUKUVAIA_MCP_CLIENT_ENABLED` (default `false`) — zero-risk
  when no remote MCP is wanted.
- Tests: 14 unit + integration (`McpClientConfigTest` 6 cases covering
  matched/unmatched URIs, env resolution, blank values, edge cases;
  `McpConnectionsCacheTest` 6 cases covering load, longest-prefix
  matching, startup-failure-safe, connection-details mapping).

**Known limitations** — documented, tracked for a follow-up plan:

- Spring AI wires `McpSyncClient` beans once at application startup from
  the connection-details snapshot. **Adding a new connection requires a
  restart** for Spring to open a session; header changes on existing
  connections take effect immediately after `cache.refresh()`. A hot-
  reload worker that registers/de-registers McpSyncClient beans on
  demand is a future enhancement.
- In-memory cache is per-instance. Multi-replica deployments relying
  on real-time cache consistency would need either pub/sub or a short
  TTL on startup reload.
- Admin endpoints are currently unauthenticated — the existing
  `ApiAuthFilter` covers other `/api/*` routes and must be applied to
  `/api/admin/*` before exposing this to non-trusted networks.

**Startup trace on a populated DB:**

```
MCP connections cache refreshed — 1 enabled connections
MCP connection details: 1 connections resolved from DB — [sl-content]
MCP: client 'sl-content' connected — 9 tools discovered
```

### Phase C — Agent integration & generic extension primitives (revised)

**Direction change after user feedback:** kukuvaia engine must stay
generic — no hardcoded domain names (`teacher`, `country`, `subject`,
`grade`, `/select-content`, `retrieve_books`). Domain personas and
commands are user-authored `.kukuvaia/` extensions. The engine only
provides the generic machinery that makes those extensions effective.

**Engine deliverables (generic, kukuvaia-side):**

1. `PersonaSpec.toolAllowList: List<String>` — optional list of tool
   names a persona can call. `null`/empty means "all". Applied at
   ChatClient build time by filtering the `ToolCallback` list against
   the allow-list. Works identically for local `@Tool` methods and
   MCP-discovered tools.
2. `SessionParamsService` — per-session `Map<String, String>` store.
   `set(sessionId, key, value)`, `unset`, `getAll`. No domain knowledge —
   keys are user-defined strings.
3. `UserCommandLoader` new YAML type `set-params` — user commands like
   `/select-content country=PL subject=math grade=7` parse arg strings
   into params dict and call `SessionParamsService`. Engine never
   mentions "select-content" or any domain field.
4. `SessionContextAdvisor` extension — when the session has non-empty
   params, inject a neutral block:
   ```
   ## Session parameters
   - country: PL
   - subject: math
   - grade: 7
   ```
   Agent uses these as it sees fit; engine doesn't prescribe meaning.
5. Tests: persona allow-list filters tools; `set-params` command writes
   and clears params; `SessionContextAdvisor` injects params block.

**User-authored extensions (NOT engine code — ship as examples in
`docs/examples/`):**

- `.kukuvaia/personas/teacher.yaml` — declares
  `toolAllowList: [search, search_text, get_item, memory_search]`,
  systemPrompt wskazuje agentowi żeby respektował session parametry
  country/subject/grade i używał `search`.
- `.kukuvaia/commands/select-content.yaml` — `type: set-params`, mapuje
  pozycyjne argi na klucze country/subject/grade.

**Same pattern reusable for any domain:** `lawyer.yaml` +
`/set-jurisdiction` for legal work, `medical.yaml` + `/set-specialty`
for medical consults. Engine unchanged.

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

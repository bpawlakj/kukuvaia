# Daemon runtime — finish kukuvaia's background "home" for agents (prerequisite for Astral)

> The Astral plan is saved separately at `~/.claude/plans/astral-plan.md`; its K0 phase is replaced by this plan.

## Context

The daemon was designed (docs/architecture/agent-orchestration-decisions.md, archived spec 2026-04-06) as the place
where kukuvaia runs background work: cron, webhooks, PG NOTIFY → guarded, audited execution. The code only half
delivers it, and only for one kind of worker:

- `DaemonAgentService.execute(...)` always calls `SubAgentFactory.execute(...)` — the daemon can host **only an LLM
  specialist**. No way to run a deterministic Java job (e.g. Astral scan), an Embabel agent, or a remote agent.
- Nothing drives it: no cron scheduler or job definitions, `PgNotifyDebouncer` has no listener, webhooks run
  **synchronously inside the HTTP request**, `/api/webhooks/alert` targets a non-existent `devops` specialist.
- Fail-closed controls required by AGENTS.md are missing: `maxToolRounds` never enforced; token usage discarded
  (`.content()`), `DaemonBudgetGuard.recordUsage` never called (budget never trips); timed-out futures not cancelled
  (run on `ForkJoinPool.commonPool`); `ExecutionContext` ignored by `LlmProviderService` (DAEMON → SmartGate, "Copilot
  opt-in" not implemented — security-review #14 is marked done but isn't); `ProviderAuditLog` not on sub-agent clients.
- No `NotificationSink`, no `/api/daemon/*` API (security-review #13/#21 reference it), `daemon_tasks` is
  specialist-only (`specialist_type NOT NULL`, statuses RUNNING/COMPLETED/FAILED, `token_usage` always 0).
- Boot's default scheduler is a single thread — a long daemon job would block `MemoryConsolidationJob`.

Goal (user decision): the daemon becomes a **runtime that hosts four kinds of workers under the same guards, audit,
triggers and notifications**: LLM specialist · deterministic Java job · Embabel (GOAP) agent · external agent via
MCP (A2A later). Jobs are defined in **YAML**.

## Target design

```
trigger (cron | webhook | pg-notify | manual API | in-process caller e.g. Slack)
   └─> DaemonRuntime.submit(job, input, trigger)
         guards: kill switch → job enabled → budget (token kinds) → overlap policy → concurrency limit
         audit:  daemon_tasks row QUEUED → RUNNING → COMPLETED | FAILED | TIMEOUT | SKIPPED
         run:    DaemonExecutor[kind].execute(invocation, limits)  on bounded daemon pool, hard deadline + cancel
         after:  record usage → budget, metrics, NotificationSink(s) with NotificationSanitizer payload
```

- `DaemonExecutor` SPI (package `ai.kukuvaia.agent.daemon.exec`): `kind()`, `ExecutionOutcome execute(JobInvocation, JobLimits)`;
  `ExecutionOutcome(status, text, JsonNode output, Usage(prompt, completion), provider, model)`.
  - `SpecialistExecutor` → `SubAgentFactory` (returns usage), DAEMON context, daemon tool allowlist.
  - `JavaJobExecutor` → Spring beans implementing `DaemonJob { String name(); JobResult run(JobContext) }`; `JobContext`
    gives deadline/cancellation, sanitized input and `callSpecialist(...)` (counted, guarded) — no direct LLM access.
  - `EmbabelAgentExecutor` → `AgentPlatform` by agent name, `ProcessOptions` with `Budget(tokens, actions)`, a
    time-limit `EarlyTerminationPolicy` + hard deadline (`killAgentProcess`), usage from `agentProcess.totalUsage()`.
  - `McpToolExecutor` → typed call of a named tool on a named `mcp_connections` client (deterministic, no LLM; args from
    the YAML template with sanitized fields only). `A2aExecutor` behind the same SPI after a spike (Java SDK is
    `a2a-java-sdk-client 1.0.0.Alpha3`).
- Existing `DaemonAgentService.execute(...)` stays as a thin facade over `DaemonRuntime` (no caller breaks).
- No new framework: Spring `TaskScheduler`/`CronTrigger`, existing guards, `SubAgentFactory`/`SubAgentGuard`, Embabel already
  in the build (AGENTS.md "build on existing orchestration").

## Job definition (YAML)

`classpath*:daemon/*.yaml` (built-ins) + `.kukuvaia/daemon/*.yaml` (CLI mode only; server-controlled in Web API mode,
per AGENTS.md trust boundary). Strict schema: unknown keys rejected, invalid file → logged and skipped, startup summary.

```yaml
name: ci-failure-analysis
description: Explain failed CI runs
kind: specialist            # specialist | java | embabel | mcp | a2a
target: analyst             # specialist name | DaemonJob bean name | Embabel agent name | {connection, tool}
input:                      # only declared, sanitized fields reach prompts/args — never raw payloads
  fields: [repository, branch, status, error]
prompt: |                   # specialist/embabel; placeholders limited to {{fields.x}}
  CI failed on {{fields.repository}}@{{fields.branch}}: {{fields.error}}
triggers:
  - cron: "0 0 2 * * MON-FRI"
  - webhook: ci             # POST /api/webhooks/ci
  - pg-notify: new_content_items
  - manual: true
provider: null              # explicit override; Copilot only if named here (opt-in)
limits: { timeout: 120s, max_tokens: 4000, max_tool_rounds: 6, max_actions: 20, token_budget: 20000 }
overlap: skip               # skip | allow (max_concurrent)
notify: [log]               # log | webhook (Slack sink arrives with Astral)
enabled: true
```

## Phases (each = one reviewable PR)

### D1 — fail-closed controls (no new features)
- `SubAgentFactory`: return `SubAgentResult(content, Usage)` from `.call().chatResponse()` (keep `String` overloads);
  **enforce `max_tool_rounds`** with a per-call counting `ToolCallback` decorator (N+1 → "tool budget exhausted");
  run on a dedicated bounded virtual-thread executor and **cancel on timeout**; add `ProviderAuditLog` to sub-agent
  clients (metrics + token log); daemon provider policy on the `tier` path.
- `LlmProviderService`: DAEMON without explicit provider → role `kukuvaia.daemon.default-provider` (default `daemon`,
  documented → SmartGate), fallback `supervisor`; **throw if the resolved provider is Copilot** unless explicitly named;
  same check in `resolveByRole` under DAEMON. Fix security-review #14 status text.
- Daemon tool allowlist `kukuvaia.daemon.allowed-tools` passed as `personaTools` → `SubAgentGuard.filterTools`
  intersection (closes the "dedicated daemon persona" open item).
- `DaemonAgentService`: record real usage (`recordUsage`, `tokenUsage`); if an LLM kind reports no usage, record the
  job's `max_tokens` (fail-closed); fix misleading comments.
- Tests: usage recorded with stub `ChatModel`; tool budget trips at N+1; timeout cancels the call; DAEMON + Copilot
  throws / explicit override allowed / tier path covered; allowlist intersection strips `save_memory`.

### D2 — runtime core, executor SPI, persistence, API
- `DaemonRuntime` (submit async → task id; `runSync` for manual/tests), bounded pool `kukuvaia.daemon.max-concurrent`
  (default 2, virtual threads), statuses, deadline, `OverlapPolicy {SKIP, ALLOW(max)}` (fixes the per-name map growth
  when callers use unique names).
- `SpecialistExecutor`, `JavaJobExecutor` + `DaemonJob`/`JobContext`; facade `DaemonAgentService.execute` delegates.
- `V24__daemon_runtime.sql` (`kukuvaia_agent.daemon_tasks`): add `kind`, `target`, `provider`, `model`,
  `prompt_tokens`, `completion_tokens` (keep `token_usage` = total), `error` (separate from `result`), `output jsonb`,
  `parent_task_id`, `queued_at`; `specialist_type` nullable; status CHECK += `QUEUED, TIMEOUT, SKIPPED, CANCELLED`;
  indexes `(name, created_at)`, `(status)`. Retention property (`kukuvaia.daemon.retention-days`, default 30) + nightly prune.
- `DaemonController` behind `ApiAuthFilter`: `GET /api/daemon/jobs`, `POST /api/daemon/jobs/{name}/run`,
  `GET /api/daemon/tasks?job&status&since`, `GET /api/daemon/tasks/{id}` (full result), `GET /api/daemon/budget`.
  Manual run and full results refused when `kukuvaia.security.dev-mode=true` unless explicitly allowed for local use.
- Tests: runtime state machine (queued→running→completed/failed/timeout/skipped), overlap, concurrency limit, budget
  exhausted → SKIPPED without executor call, Java job with deadline, migration test (`KUKUVAIA_TEST_DB_URL` pattern),
  controller auth.

### D3 — job definitions, triggers, notifications
- `DaemonJobLoader` + `DaemonJobRegistry` (YAML above, strict validation, Web-API-mode restriction).
- Dedicated `ThreadPoolTaskScheduler` for daemon cron (pool 2) so `MemoryConsolidationJob` is never blocked;
  `CronTrigger` per job; `kukuvaia.daemon.enabled=false` kill switch stops scheduling and submissions.
- Webhooks: generic `POST /api/webhooks/{route}` → job by `webhook:` trigger; field extraction via
  `PayloadSanitizer.extractFields(raw, job.input.fields)`; returns **202 + task id** (no more synchronous LLM call in the
  request). `/ci` and `/alert` become built-in YAML jobs; fix `alert` (add `devops` specialist or retarget to `analyst`).
- PG NOTIFY: `PgNotifyListener` (dedicated pgjdbc connection, `LISTEN` per declared channel, reconnect with backoff) →
  existing `PgNotifyDebouncer` → job (batched payload as sanitized fields); bind debouncer `shutdown()` to lifecycle.
- `NotificationSink` SPI + `LogNotificationSink` (default) + `WebhookNotificationSink` (HMAC-signed POST, payload from
  `NotificationSanitizer` = summary only; full result only via authenticated API).
- Tests: loader (valid/invalid/unknown keys/web-mode), cron registration, webhook 202 + task created + raw payload never
  in prompt, PG listener with Testcontainers-style gated test, sinks (signature, sanitized payload).

### D4 — Embabel executor
- `EmbabelAgentExecutor`: resolve agent by name from `AgentPlatform.agents()`, bindings from sanitized input,
  `ProcessOptions` with `Budget(tokens = min(job.token_budget, remaining daily), actions = max_actions)`, time-limit
  termination policy + hard deadline → `killAgentProcess`; outcome/usage from `AgentProcess` (`totalUsage()`, status).
- Guards for Embabel's own LLM path (it calls `ChatModel` directly, so ChatClient advisors don't apply): daemon runs give
  Embabel a `GuardedChatModel` decorator (adds the `ToolResultSanitizingAdvisor` "tool results are data" system note,
  `ProviderAuditLog`-equivalent metrics, usage capture) and resolve roles through the DAEMON provider policy. No
  kukuvaia tools exposed to Embabel agents in v1 (only their own declared actions).
- Tests: `PingAgent` (deterministic) end-to-end via YAML job; `ResearchAgent` with a stub `ChatModel` → usage recorded,
  token budget terminates early, time limit kills, Copilot refused.

### D5 — external agents (MCP now, A2A spike)
- MCP client streamable HTTP from DB (moved here from Astral K0): `V25__mcp_connections_transport.sql` (`transport`
  sse|streamable-http, `endpoint` `/mcp`), `HttpClientStreamableHttpTransport` + existing `PerConnectionHeaderCustomizer`;
  fix SSE auth headers via `McpClientCustomizer<HttpClientSseClientTransport.Builder>`.
- `McpToolExecutor`: `kind: mcp`, `target: {connection, tool}`, args template from sanitized fields; result text/JSON
  through tool-result sanitization; timeout; zero tokens (remote side's cost is out of scope, logged as such).
- A2A: half-day spike with `a2a-java-sdk-client` (alpha) against a sample agent; implement `A2aExecutor` only if the
  spike is green, otherwise keep `kind: a2a` rejected by the loader with a clear message.
- Tests: stub MCP server (JDK `HttpServer`, JSON-RPC + `mcp-session-id`) receives `Authorization`; executor timeout;
  session re-init after 404.

## Security mapping (AGENTS.md / security-review)

| Requirement | Where |
|---|---|
| maxTokens + max tool rounds/actions + timeout, fail closed (#DoS) | D1 (specialist), D4 (Embabel), D2 deadline for all kinds |
| Budget actually enforced (#15 follow-up) | D1 usage → `recordUsage`; D2 SKIPPED on exhaustion; D4 Embabel `Budget` |
| DAEMON → SmartGate, Copilot opt-in (#14) | D1 |
| Webhook: rate limit → auth → sanitize, no raw payload (#4, #5, #10) | unchanged filters + D3 declared fields, 202 async (backpressure) |
| Notifications summary-only, full result via authenticated API (#13, #21) | D2 API, D3 sinks |
| Cron overlap (#17), PG NOTIFY storm (#16) | D2 overlap policy, D3 listener → existing debouncer |
| ToolResultSanitizingAdvisor on every LLM path | specialist clients (exists), D4 `GuardedChatModel` for Embabel |
| `.kukuvaia/` server-controlled in Web API mode; YAML schema validation | D3 loader |
| Daemon tool restriction (open item) | D1 `kukuvaia.daemon.allowed-tools` |
| "Daemon uses service-account credentials, never personal tokens" | documented; Astral's personal-token pilot is a recorded, temporary exception |

## Critical files

- `kukuvaia-core/.../agent/subagent/SubAgentFactory.java`, `SubAgentGuard.java`, `SubAgentSpecLoader.java`
- `kukuvaia-core/.../agent/daemon/DaemonAgentService.java`, `DaemonBudgetGuard.java`, `DaemonScheduleGuard.java`,
  `PgNotifyDebouncer.java`, `NotificationSanitizer.java`; new `DaemonRuntime`, `exec/*`, `DaemonJobLoader`, `DaemonController`
- `kukuvaia-core/.../api/webhook/WebhookController.java`
- `kukuvaia-provider/.../service/LlmProviderService.java`, `ChatModelCache.java`, `ProviderAuditLog.java`
- `kukuvaia-agents/.../config/EmbabelModelBridgeConfig.kt` (DAEMON-aware model provider / `GuardedChatModel`)
- `kukuvaia-core/.../config/McpClientConfig.java`, `mcp/*` (D5)
- `kukuvaia-app/src/main/resources/application.yaml`, `db/migration/V24__daemon_runtime.sql`, `V25__mcp_connections_transport.sql`
- Docs: `docs/architecture/agent-orchestration-decisions.md` (daemon section → living description of the runtime),
  `security-review.md` statuses (#14 etc.), new `docs/architecture/daemon-runtime.md`; save this plan as
  `docs/work/028-daemon-runtime/plan.md` (Astral becomes 029).

## Config (`application.yaml`, safe defaults)

```yaml
kukuvaia:
  daemon:
    enabled: ${KUKUVAIA_DAEMON_ENABLED:true}
    default-provider: ${KUKUVAIA_DAEMON_PROVIDER:daemon}   # role → SmartGate model
    daily-token-budget: 100000
    budget-alert-threshold: 0.8
    max-concurrent: 2
    allowed-tools: []          # empty = specialist allowlists only; set to narrow all daemon runs
    retention-days: 30
    scheduler-pool-size: 2
```

## Out of scope

Multi-instance locking (PG advisory locks) · Go CLI `/daemon` commands (CLI stays thin; can call the new API later) ·
Slack sink (comes with Astral K3 on the same SPI) · migrating `MemoryConsolidationJob` to a `DaemonJob` (possible later)
· reusing `agent_runs` as the store (different schema/lifecycle; `daemon_tasks` stays the audit log).

## Verification

1. Each phase: `./gradlew clean build` green (currently 651 tests) + new tests listed per phase.
2. After D3, with the app and local Postgres running:
   - `GET /api/daemon/jobs` lists built-ins (`ci-failure-analysis`, `alert-investigation`) and a sample Java job.
   - `POST /api/webhooks/ci` (signed) → **202** + task id; `GET /api/daemon/tasks/{id}` → COMPLETED with
     `prompt_tokens/completion_tokens > 0`, provider ≠ Copilot; budget decreases in `GET /api/daemon/budget`.
   - Set `daily-token-budget: 1` → next run SKIPPED, executor not called.
   - A cron job with `timeout: 2s` and a slow stub → TIMEOUT, call cancelled.
   - `NOTIFY new_content_items, '…'` ×50 within 5 s → one debounced job run.
3. After D4: YAML job `kind: embabel, target: PingAgent` → COMPLETED; ResearchAgent with stub model hits token budget → early termination recorded.
4. After D5: YAML job `kind: mcp` against the local toolkit server (`sl-aipdlc serve --http`) → COMPLETED, `Authorization` sent.

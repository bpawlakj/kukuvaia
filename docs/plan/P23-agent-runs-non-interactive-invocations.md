# P23 — Agent Runs: Generic Non-Interactive Invocation Model

**Created:** 2026-04-22
**Status:** Draft
**Module:** kukuvaia-core, kukuvaia-app (migration V19), kukuvaia-engine `.kukuvaia/` extensions
**Depends on:**
- Existing `SPRING_AI_CHAT_MEMORY` / `sessions` / `users` / `kukuvaia.memories` model — **UNCHANGED**
- Existing Spring AI `ChatClient` and `ChatModel` configuration (reused, no modification)
- Existing `EmbeddingService` (local ONNX all-MiniLM-L6-v2 or OpenAI remote; reused, no modification)
- pgvector extension (already enabled for `kukuvaia.memories.embedding`)
- Existing TOOL.md pattern (`.kukuvaia/tools/**/TOOL.md`) — **PATTERN EXTENDED** to schedules, not modified

**Driven by:** integration need from an external project
(`/Users/bartosz.pawlak/Projects/idb` — Sanoma "Ready to Impersonate"), but the feature is
designed **fully generic** — applicable to any scenario where a non-human actor invokes
kukuvaia for a one-shot analysis with persistent, retrievable output.

---

## Problem

Kukuvaia today models every agent interaction as a **session** owned by a human **user**:

```
users ──(1:N)──▶ sessions ──(1:1)──▶ SPRING_AI_CHAT_MEMORY
                     │
                     └──▶ MemoryExtractionService ──▶ kukuvaia.memories
```

This works beautifully for interactive chat, but it has no clean slot for the growing
class of **non-interactive invocations** where the caller is a system/service/another agent:

1. A **scheduler** that periodically pulls data from an external source (e.g., CloudWatch
   Logs, Datadog alerts, Prometheus metrics) and wants the LLM to analyze it.
2. A **webhook** receiving Jira ticket events, Git push events, CI failures, etc.
3. Another **AI agent** ("agent-to-agent" handoff) requesting a specialized analysis.
4. A **CLI/API** one-shot prompt that should be retrievable later but isn't worth
   creating a human-facing session for.

Today, the only ways to handle these are all compromises:

- **Create a fake "system user"** (e.g., `user_id = "system:scheduler"`) and a session per run.
  - Pollutes the `users` and `sessions` tables with non-human rows that leak into every UI
    and query that lists users/sessions.
  - Forces non-interactive flows through the interactive-session pipeline
    (message-by-message `ChatMemory`, extraction cursors, async memory extraction),
    which is semantically wrong — there is no "conversation" to extract facts from;
    the whole run IS the fact.
- **Reference the aspirational `daemon_tasks` table** (`DaemonAgentService` writes to it,
  but the Flyway migration was never shipped, so the code fails at runtime).
  - And even if shipped, `daemon_tasks` has no embedding field, no structured input/output,
    no similarity search — it's a glorified job log.
- **Abuse `kukuvaia.memories` with a synthetic memory_type.**
  - Conflates curated knowledge (what `MemoryExtractionService` produces — "facts worth
    remembering") with raw run output (logs + analysis blobs). Semantic rot.

The industry (LangSmith, OpenAI Assistants API, LangGraph) converged years ago on a
different noun for this: **Run**. A run is one execution of an agent — queued, in-progress,
completed — with structured inputs, structured outputs, a model, token usage, and timing.
Runs can be grouped (LangGraph threads) or chained (LangSmith `parent_run_id`). They are
peer-concepts to chat threads, not children of them.

Kukuvaia needs the same abstraction.

## Goal

Introduce a first-class **`agent_runs`** persistence model that:

1. Records a single non-interactive invocation of the agent by any kind of caller
   (scheduler, webhook, API, agent, CLI — the caller declares its kind).
2. Stores structured input, structured output, model metadata, token usage, granular
   timing, and a vector embedding of the output for similarity retrieval.
3. Supports **chaining** via `parent_run_id` (e.g., scheduled run → enrichment run →
   ticket-draft run) and **grouping** via optional `thread_id` (future-proof for
   LangGraph-style run grouping).
4. Plugs into **a new generic scheduler** that reads schedule definitions from
   `.kukuvaia/schedules/*.md` files (same YAML-frontmatter pattern as existing
   `.kukuvaia/tools/**/TOOL.md`).
5. Plugs into **a new generic `aws` tool** (in `.kukuvaia/tools/aws/`) that exposes
   AWS SDK v2 operations to Lua tool scripts via a single `aws.call(service, op, params)`
   binding. CloudWatch Logs only in v1; other services added on demand.

Crucially: **no change to `users`, `sessions`, `SPRING_AI_CHAT_MEMORY`,
`MemoryExtractionService`, or `kukuvaia.memories`**. Interactive flow is untouched. This
is a peer system, not a refactor.

## Non-goals

- **Do NOT replace sessions/memory for interactive chat.** Interactive conversations stay
  in the existing pipeline. Agent runs are a *separate* lane.
- **Do NOT auto-promote agent runs to `kukuvaia.memories`.** Agent runs stay in
  `agent_runs`. If a future plan wants to distill facts from a run into long-term memory,
  that is an explicit, separately-scoped promotion step — not a default.
- **Do NOT merge the aspirational `daemon_tasks` table.** That table was never migrated.
  `DaemonAgentService` currently fails at runtime when invoked. A follow-up plan may
  repurpose `DaemonAgentService` to write to `agent_runs` instead, but this plan does not
  touch `DaemonAgentService`. v1 scope is schema + scheduler + one generic tool only.
- **Do NOT build an admin UI for agent runs in v1.** Browsing is via SQL or the existing
  kukuvaia-admin (a small read-only panel can be a follow-up; not blocking).
- **Do NOT build webhook ingestion for agent runs in v1.** The scheduler pattern covers
  the immediate need. Webhook → agent run is trivial later (any `@PostMapping` handler can
  `AgentRunService.createAndExecute(...)`).
- **Do NOT add more AWS services beyond CloudWatch Logs in v1.** Handler stubs for S3 /
  SSM / Secrets Manager can land as separate small PRs when needed.

## Current State (what exists today)

| Component | Status | Relevance |
|---|---|---|
| `users`, `sessions`, `SPRING_AI_CHAT_MEMORY` | Shipped | Untouched. |
| `kukuvaia.memories` + pgvector + `EmbeddingService` | Shipped | Pattern reused — same embedding model, same pgvector index type. |
| `MemoryExtractionService` | Shipped | Untouched. Does not see agent runs. |
| `DaemonAgentService` + `DaemonBudgetGuard` | Code exists, table `daemon_tasks` **not migrated** (fails at runtime) | Out of scope. Not merged, not extended. |
| `ChatClient` / `ChatModel` via Spring AI | Shipped | Reused directly by the new scheduler path. |
| `ToolExecutor` + Lua runtime (`http.*`, `fs.*` bindings) | Shipped | Extended with a new `aws.*` binding table. |
| TOOL.md pattern (`.kukuvaia/tools/**/TOOL.md`) | Shipped | Extended by analogy to `.kukuvaia/schedules/*.md`. |
| `MEMORY_CONSOLIDATION_CRON` env var | Shipped | Untouched. Different feature. |

## Architecture

```
                                                                    
  External invokers (all equal peers, no hierarchy)
 ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐
 │  Scheduler        │  │  Webhook          │  │  Another agent /   │
 │  (cron-triggered) │  │  (future)         │  │  CLI one-shot     │
 └─────────┬─────────┘  └─────────┬─────────┘  └─────────┬─────────┘
           │                      │                       │         
           └──────────────────────┴───────────────────────┘         
                                  │                                  
                                  ▼                                  
 ┌────────────────────────────────────────────────────────────────┐ 
 │  AgentRunService.createAndExecute(spec)                         │
 │   1. INSERT agent_runs row (status='queued', invoker_*, input)  │
 │   2. UPDATE status='in_progress', started_at=now()              │
 │   3. If spec has a tool: ToolExecutor.execute(tool, params)     │
 │   4. If spec has an llm_followup: ChatClient.prompt(...).call() │
 │      └── capture prompt_tokens / completion_tokens / model      │
 │   5. EmbeddingService.embed(output_text) → embedding            │
 │   6. UPDATE agent_runs row (status='completed', output, ...)    │
 │   7. On failure: status='failed', error_message, completed_at   │
 │                                                                  │
 │  AgentRunRepository.findSimilar(embedding, limit, filter)       │
 │   └── cosine via hnsw index, optional WHERE invoker_name/tags   │
 └───────────────────────────────────┬─────────────────────────────┘ 
                                     │                                
                                     ▼                                
 ┌────────────────────────────────────────────────────────────────┐ 
 │  kukuvaia.agent_runs (NEW — migration V19)                      │ 
 │  PEER to sessions/memories, not a child.                        │ 
 │  No FK to users. No FK to sessions.                             │ 
 └────────────────────────────────────────────────────────────────┘ 
                                                                    
 ┌────────────────────────────────────────────────────────────────┐ 
 │  Existing flow — UNCHANGED                                       │
 │  users → sessions → SPRING_AI_CHAT_MEMORY → MemoryExtractionSvc │ 
 │                                         → kukuvaia.memories     │ 
 └────────────────────────────────────────────────────────────────┘ 
```

## Design

### 1. Schema — `kukuvaia.agent_runs` (migration V19)

Field set designed by hybridizing the industry standard (OpenAI Assistants API Run,
LangSmith Run, LangGraph Thread/Run) with kukuvaia's existing pgvector infrastructure.

```sql
-- V19__create_agent_runs.sql

CREATE TABLE kukuvaia.agent_runs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Grouping (LangGraph-style Thread; nullable — used only if Phase-2 features need it)
    thread_id          UUID,

    -- Hierarchy (LangSmith-style parent_run_id — for chained analyses)
    parent_run_id      UUID REFERENCES kukuvaia.agent_runs(id),

    -- Actor (who invoked the run — generic, never NULL)
    invoker_name       VARCHAR(255) NOT NULL,
        -- free-form identifier, e.g. 'cloudwatch-scheduler',
        -- 'jira-webhook-listener', 'external-ai-assistant', 'manual-cli:bartek'
    invoker_kind       VARCHAR(50)  NOT NULL,
        -- enum-like string; v1 accepts:
        -- 'scheduler' | 'webhook' | 'api' | 'agent' | 'cli' | 'other'

    -- Input
    input_type         VARCHAR(100) NOT NULL,
        -- caller-supplied classification, e.g.
        -- 'cloudwatch_logs' | 'jira_ticket' | 'agent_handoff' | 'manual_query'
    input              JSONB NOT NULL,
        -- structured payload, schema is caller-defined
    instructions       TEXT,
        -- effective system prompt passed to the LLM (nullable if no LLM call)

    -- Execution metadata
    model              VARCHAR(100),
        -- which LLM was used, e.g. 'claude-sonnet-4-6'; null if no LLM call
    status             VARCHAR(20) NOT NULL,
        -- 'queued' | 'in_progress' | 'completed' | 'failed' | 'cancelled'
        -- | 'expired' | 'skipped'
    error_code         VARCHAR(100),
    error_message      TEXT,

    -- Output
    output             JSONB,
        -- structured result; null if run failed before producing output
    output_text        TEXT,
        -- flat text version for embedding + quick preview
    embedding          vector(384),
        -- pgvector; uses the same model as kukuvaia.memories (all-MiniLM-L6-v2)
        -- so the two spaces are comparable if ever useful

    -- Token / cost (standard split, matches ProviderAuditLog naming)
    prompt_tokens      INT,
    completion_tokens  INT,
    total_tokens       INT GENERATED ALWAYS AS
                       (COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0)) STORED,

    -- Timing (granular, OpenAI Run pattern)
    queued_at          TIMESTAMPTZ,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Optional metadata
    tags               TEXT[] NOT NULL DEFAULT '{}',
    severity           VARCHAR(20)
        -- optional domain-level priority; 'low' | 'medium' | 'high' | 'critical'
);

-- Browse by invoker + time
CREATE INDEX idx_ar_invoker_time    ON kukuvaia.agent_runs (invoker_name, created_at DESC);

-- Filter by input classification
CREATE INDEX idx_ar_input_type      ON kukuvaia.agent_runs (input_type, created_at DESC);

-- Filter by run status (e.g. find all failed, find in-progress)
CREATE INDEX idx_ar_status          ON kukuvaia.agent_runs (status, created_at DESC);

-- Traversal by thread or parent — sparse indices
CREATE INDEX idx_ar_thread          ON kukuvaia.agent_runs (thread_id, created_at)
    WHERE thread_id IS NOT NULL;
CREATE INDEX idx_ar_parent          ON kukuvaia.agent_runs (parent_run_id)
    WHERE parent_run_id IS NOT NULL;

-- Severity filter (sparse — most runs won't set it)
CREATE INDEX idx_ar_severity        ON kukuvaia.agent_runs (severity, created_at DESC)
    WHERE severity IS NOT NULL;

-- Vector similarity (hnsw; parameters match kukuvaia.memories.embedding)
CREATE INDEX idx_ar_embedding_cos   ON kukuvaia.agent_runs
    USING hnsw (embedding vector_cosine_ops);

-- Tag filter
CREATE INDEX idx_ar_tags            ON kukuvaia.agent_runs USING gin (tags);

COMMENT ON TABLE kukuvaia.agent_runs IS
    'Non-interactive agent invocations (P23). Peer to sessions — never tied to a user. '
    'Populated by the generic scheduler (.kukuvaia/schedules/*.md), webhooks, API, or '
    'agent-to-agent handoffs.';
```

### 2. Service layer — `AgentRunService` + `AgentRunRepository`

Both are new classes, no change to existing services.

**`AgentRunSpec`** (input data class, pure Kotlin/record):

```
invokerName: String                 // required
invokerKind: String                 // required: 'scheduler' | 'webhook' | 'api' | 'agent' | 'cli' | 'other'
inputType: String                   // required: caller-defined classification
input: JsonNode                     // required
toolName: String?                   // optional — if set, AgentRunService calls ToolExecutor
toolParams: Map<String, Any>?       // params for the tool
llmFollowup: LlmFollowupSpec?       // optional
parentRunId: UUID?                  // optional
threadId: UUID?                     // optional
tags: List<String>                  // optional
taskClass: String?                  // optional; hint for future P19 complexity-driven routing
                                    // examples: 'log_triage', 'ticket_drafting', 'summarization'
```

**`LlmFollowupSpec`**:

```
systemPrompt: String                // becomes agent_runs.instructions
userPromptTemplate: String?         // default: tool output as-is
skipIfEmpty: Boolean                // default: true
storeEmbedding: Boolean             // default: true
similarityContext: SimilarityContextSpec?   // null = off
modelPreference: ModelPreferenceSpec?       // null = use ChatClient default
maxOutputTokens: Int?               // cap on completion size; null = model default
promptCaching: Boolean              // default: false; when true, enables provider prompt caching
                                    // for identical system prompts across runs (Anthropic/OpenAI)
```

**`ModelPreferenceSpec`** (see also §Design / 6 below):

```
primary: String                     // model id for first-pass run, e.g. 'claude-haiku-4-5'
escalateTo: String?                 // optional model id for re-run when trigger fires
escalationTrigger: String?          // SpEL-like expression evaluated against the first-pass
                                    // parsed JSON output + run metadata; if true, re-run with escalateTo
                                    // examples:
                                    //   "output.needs_escalation == true"
                                    //   "output.severity in {'high','critical'}"
                                    //   "output.needs_escalation == true or status == 'failed'"
```

**`SimilarityContextSpec`** (for run-to-run recall before LLM call):

```
enabled: Boolean                    // default: false
topK: Int                           // default: 3
maxAgeDays: Int?                    // default: 30
filterByInvokerName: Boolean        // default: true — only recall runs from same invoker
filterByTags: List<String>?         // additional tag filter
```

**`AgentRunService`** — one class, ~150 lines, the lifecycle:

1. Insert row with `status='queued'`, `queued_at=now()`.
2. Flip to `in_progress`, `started_at=now()`.
3. If `toolName` set: call `ToolExecutor.execute(toolName, toolParams)`; on error →
   `status='failed'`, `error_message`, return.
4. Build LLM input:
   - Start from tool result (or direct `input` if no tool).
   - If `llmFollowup.similarityContext.enabled`: `AgentRunRepository.findSimilar(...)`
     → prepend top-k past outputs to the user prompt as context.
5. If `skipIfEmpty` and result is empty: `status='skipped'`, `completed_at=now()`, return.
6. Resolve target model for the call:
   - If `llmFollowup.modelPreference.primary` is set → use that model.
   - Else if `taskClass` is set AND P19 complexity-driven routing is available → delegate
     model selection to routing.
   - Else → use `ChatClient` default.
7. If `llmFollowup.maxOutputTokens` is set → apply as a completion cap on the request.
8. If `llmFollowup.promptCaching == true` → enable provider-specific prompt caching
   (Anthropic `cache_control`, OpenAI implicit caching). Kukuvaia marks the system
   prompt as cacheable since it is identical across runs of the same schedule.
9. Call `ChatClient.prompt().system(systemPrompt).user(userPrompt).call()`.
   Capture `ChatResponse.metadata.usage` → prompt/completion tokens, `model`.
10. **Escalation check** (see §Design / 6): if `modelPreference.escalationTrigger` is set
    and evaluates `true` against the first-pass parsed JSON output, **re-run** step 9
    with `modelPreference.escalateTo` as the target model. The second run overrides the
    first-pass output in the final persisted row; token usage is the **sum** of both
    runs; `model` field records the escalation model (the one whose output is kept).
    A single `escalated_from_model` field is not added to the schema (v1) — escalation
    is inferred by presence of multi-model runs in tracing/metrics if ever needed.
11. If `storeEmbedding`: `EmbeddingService.embed(outputText)` → `embedding`.
12. Update row: `output`, `output_text`, `embedding`, `prompt_tokens`,
    `completion_tokens`, `model`, `status='completed'`, `completed_at=now()`.

**`AgentRunRepository`** — JdbcTemplate-based (matches repository style in kukuvaia-memory).
Methods:
- `insert(spec, status) → UUID` (pre-execute insert)
- `updateStarted(id)`, `updateCompleted(id, ...)`, `updateFailed(id, ...)`, `updateSkipped(id)`
- `findById(id): AgentRun?`
- `findByInvokerName(name, limit): List<AgentRun>`
- `findSimilar(embedding, topK, maxAgeDays, filter): List<AgentRun>` — cosine via hnsw

### 3. Generic scheduler — reads `.kukuvaia/schedules/*.md`

New `ScheduleLoader` + `ScheduledTaskRunner` in `kukuvaia-core`:

- On `@PostConstruct`: scan `${kukuvaia.schedules.directory}` (default
  `classpath:.kukuvaia/schedules/`) for `*.md`, parse YAML frontmatter using the SAME
  parser already used for `TOOL.md` (reuse, do not duplicate).
- Validate: `name` unique, `cron` parseable by Spring `CronTrigger`, `tool` exists in
  `.kukuvaia/tools/` or is the literal `_llm_only_` (no-tool LLM call).
- For each `enabled: true` schedule: register with Spring `TaskScheduler` + `CronTrigger`.
- Each run calls `AgentRunService.createAndExecute(spec)` with:
  - `invokerName = "scheduler:${schedule.name}"`
  - `invokerKind = "scheduler"`
  - `inputType = <from schedule frontmatter>`
  - `input = <resolved params after placeholder substitution>`
  - `toolName`, `toolParams`, `llmFollowup` — from frontmatter
  - `tags = <from frontmatter>`

**Placeholder resolution**: `{{now}}`, `{{now - 15m}}`, `{{now - 1h}}`, `{{now - 1d}}`,
`{{env.VAR}}`, `{{last_run_end_time}}` (pulled from a small `schedule_state` table
— added in the same migration V19 below).

**Schedule state table** (also V19):

```sql
CREATE TABLE kukuvaia.schedule_state (
    schedule_name      VARCHAR(255) PRIMARY KEY,
    last_run_id        UUID REFERENCES kukuvaia.agent_runs(id),
    last_run_end_time  TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Schedule file format (example, template only — lives in the consuming project, not shipped with kukuvaia):**

```yaml
---
name: <unique schedule name>
description: <human-readable>
cron: "<6-field Spring cron>"
enabled: true

tool: <tool name, or _llm_only_>
params:
  <key>: <value or templated>

llm_followup:
  enabled: true
  system_prompt: |
    <multiline>
  skip_if_empty: true
  store_embedding: true
  similarity_context:
    enabled: true
    top_k: 3
    max_age_days: 30
    filter_by_invoker_name: true

input_type: <caller-defined classification>
tags: [tag1, tag2]
---
<body reserved for future Lua post-processing — unused in v1>
```

### 4. Generic `aws` tool — `.kukuvaia/tools/aws/`

Lua binding layer `aws.call(service, operation, params_json, region?)` exposed to the
existing Lua runtime, backed by AWS SDK v2 (`software.amazon.awssdk:*`, BOM 2.28.x).

**`AwsLuaBindings.kt`** — registers the `aws` global table. ~40 lines.

**`AwsInvoker.kt`** — dispatcher with per-service handler:
- v1: `CloudWatchLogsHandler` — operations: `filter_log_events`, `start_query`,
  `get_query_results`, `start_query_and_wait`, `describe_log_groups`.
- Handler methods build the SDK request from parsed JSON, execute, serialize response
  to JSON string.
- Whitelist check against `config.yaml` `allowed_services`.
- Enforces `max_response_bytes` + `timeout_ms` from config.
- Catches SDK exceptions → structured error JSON with `aws_request_id`.

**`.kukuvaia/tools/aws/TOOL.md`**:

```yaml
---
name: aws
description: Generic AWS access via AWS SDK v2. v1 supports only 'cloudwatchlogs'. Credentials from default provider chain (env / EC2 instance role). Region from config.yaml or per-call override.
readOnly: false
concurrencySafe: true
parameters:
  service:
    type: string
    description: "AWS service ID. v1 supports only 'cloudwatchlogs'."
    required: true
  action:
    type: string
    description: "Operation name (e.g. 'start_query_and_wait')."
    required: true
  params:
    type: string
    description: "JSON string with operation-specific params."
    required: true
  region:
    type: string
    description: "Region override (default from config.yaml)."
    required: false
---
- lua: |
    local region_to_use = region or (config and config.region) or "eu-west-1"
    return aws.call(service, action, params, region_to_use)
```

**`.kukuvaia/tools/aws/config.yaml`**:

```yaml
region: eu-west-1
allowed_services:
  - cloudwatchlogs
max_response_bytes: 1048576
timeout_ms: 30000
cloudwatchlogs:
  max_query_results: 1000
  query_poll_interval_ms: 500
  query_timeout_ms: 120000
```

### 5. Configuration (`application.yaml` additions)

```yaml
kukuvaia:
  aws:
    default-region: eu-west-1
  schedules:
    directory: classpath:.kukuvaia/schedules/
    reload-on-startup: true
    # reload-on-change: false  — file watcher is a follow-up
  agent-runs:
    # Maximum escalation depth; prevents pathological feedback loops if a trigger
    # expression ever evaluates true indefinitely. Default 1 = primary + one escalation.
    max-escalation-depth: 1
    # Cap on re-runs per agent_run row. Safety guard.
    max-reruns: 2
```

### 6. Model selection and escalation

Agent runs benefit from cost-aware model selection: the task (log triage, ticket
drafting, routine summarization) is usually best served by a small frontier model,
with a larger model reserved for cases where the small one flags uncertainty. This
subsection specifies the minimum viable mechanism. A fuller integration with kukuvaia's
planned routing infrastructure (P18 / P19 / P20) is a follow-up, not a dependency.

**Three layered mechanisms, in precedence order:**

1. **Explicit per-call preference** — `LlmFollowupSpec.modelPreference.primary` takes
   precedence over everything else. Schedule author has full control.
2. **Task-class routing** — if `AgentRunSpec.taskClass` is set AND a routing resolver
   bean is registered (provided by P19 once shipped), `AgentRunService` delegates model
   selection to it. Schedule only declares its task class (`log_triage`,
   `ticket_drafting`, etc.); routing decides the model.
3. **ChatClient default** — fallback. Uses whatever model is configured on the primary
   `ChatClient` bean.

**Escalation protocol** (when `modelPreference.escalateTo` is set):

- First-pass call uses `primary`.
- After the call, parse the model output as JSON (use existing kukuvaia JSON parser;
  reuse the sanitizing path from `SanitizingChatMemoryRepository` if needed for
  robustness against non-JSON edge cases → in that case treat as `needs_escalation=false`).
- Evaluate `escalationTrigger` against a small context:
  - `output` — parsed JSON of the assistant response (or empty object if not JSON)
  - `status` — current run status (always `in_progress` at this point)
  - `prompt_tokens`, `completion_tokens`, `total_tokens` — usage of first pass
- Expression language: **SpEL** (Spring Expression Language, already a Spring
  dependency). Examples from the integration doc:
  ```
  output.needs_escalation == true
  output.severity in {'high', 'critical'}
  output.needs_escalation == true or (output.total_entries != null and output.total_entries > 100)
  ```
- If the expression evaluates to `true`, run a second call with `escalateTo`. The second
  call replaces the persisted `output` / `output_text` / `model`. Token usage is
  **summed** across both calls.
- `max-escalation-depth` from `application.yaml` hard-limits recursion (default 1 =
  primary + one escalation; never a third).
- If the expression fails to evaluate (malformed, references missing field),
  log a warning and **do not escalate** (fail safe toward cheaper model).

**Prompt caching** — when `LlmFollowupSpec.promptCaching == true`:
- Anthropic models: inject `cache_control: { type: "ephemeral" }` on the system message
  block. Requires Spring AI Anthropic adapter support (check version; may need minor
  adapter extension if not yet exposed).
- OpenAI models: no code change — OpenAI applies implicit caching automatically for
  prompts with identical prefixes ≥ 1024 tokens. The flag stays informational.
- Cacheable content is the system prompt only. User prompt varies per run (log entries
  change) so caching it would be counterproductive.

**Metrics emitted (extends §Metrics & observability):**

- `kukuvaia_agent_run_model_calls_total{model, tier="primary"|"escalation", status}` — counter
- `kukuvaia_agent_run_escalations_total{schedule, primary_model, escalated_to_model}` — counter
- `kukuvaia_agent_run_prompt_cache_hits_total{model}` — counter (when provider exposes it)

**Testing requirements for this subsection:**

- Unit test: escalation trigger evaluation for ~8 SpEL expressions (true/false/malformed).
- Unit test: `max-escalation-depth = 0` disables escalation even if trigger fires.
- Integration test (mocked `ChatClient`): two-tier happy path — first call returns
  `{needs_escalation: true}`, second call returns final output; verify persisted row
  has the second output, `model = escalateTo`, `total_tokens = sum(both)`.
- Integration test: malformed JSON output from first pass → no escalation, warning
  logged, first-pass output persisted as-is.

## File layout

```
kukuvaia-engine/kukuvaia-core/src/main/kotlin/ai/kukuvaia/core/
├── agentrun/
│   ├── AgentRun.kt                     # data class
│   ├── AgentRunSpec.kt                 # input dto + nested spec classes
│   ├── AgentRunService.kt              # lifecycle orchestration
│   ├── AgentRunRepository.kt           # JdbcTemplate, incl. findSimilar(embedding, ...)
│   └── AgentRunStatus.kt               # enum wrapper (string-valued)
├── aws/
│   ├── AwsConfig.kt                    # @ConfigurationProperties
│   ├── AwsLuaBindings.kt               # implements LuaBindingRegistrar (reuse existing SPI)
│   ├── AwsInvoker.kt                   # dispatcher
│   └── handlers/
│       └── CloudWatchLogsHandler.kt
└── scheduler/
    ├── ScheduledTaskRunner.kt          # @PostConstruct register CronTrigger tasks
    ├── ScheduleDefinition.kt           # parsed YAML frontmatter → data class
    ├── ScheduleLoader.kt               # scans schedules dir, reuses TOOL.md parser
    ├── PlaceholderResolver.kt          # {{now}}, {{last_run_end_time}}, {{env.X}}, …
    └── ScheduleStateRepository.kt      # last_run_end_time for dedup

kukuvaia-engine/kukuvaia-app/src/main/resources/db/migration/
└── V19__create_agent_runs.sql          # both agent_runs and schedule_state tables

kukuvaia-engine/.kukuvaia/tools/
└── aws/
    ├── TOOL.md
    └── config.yaml

kukuvaia-engine/.kukuvaia/schedules/
└── .gitkeep                            # kukuvaia itself ships zero schedules;
                                        # consuming projects drop their *.md here
```

## Invariants — what must remain true

1. **No row in `users` / `sessions` / `SPRING_AI_CHAT_MEMORY` is created or modified as a
   side effect of any agent-run operation.** Interactive flow is untouched.
2. **No row in `kukuvaia.memories` is created or modified as a side effect of any
   agent-run operation.** `MemoryExtractionService` never sees agent runs.
3. **`agent_runs` has no FK to `users` or `sessions`.** Runs are identifiable by
   `invoker_name` only, which is a free-form string.
4. **The Lua runtime available to agent runs is the same runtime used by interactive
   tools.** `aws.call` is an additive binding; existing `http.*` / `fs.*` are unchanged.
5. **`ChatClient` / `ChatModel` / `EmbeddingService` configuration is reused as-is.**
   No duplicated beans, no alternate model configs. Routing/fallback/budget rules that
   already apply to interactive `ChatClient` calls apply automatically to agent-run
   LLM calls (they go through the same `ChatClient`).

Any PR that violates one of the above must be rejected in review.

## Phases

### Phase A — Schema + Service + Repository (core persistence)
- V19 migration with `agent_runs` + `schedule_state`
- `AgentRun`, `AgentRunSpec`, `LlmFollowupSpec`, `SimilarityContextSpec`, `AgentRunStatus`
- `AgentRunRepository` (all methods)
- `AgentRunService.createAndExecute()` with tool + LLM + embedding lifecycle
- Unit tests for repository (Testcontainers Postgres + pgvector)
- Unit tests for service with mocked `ToolExecutor`, `ChatClient`, `EmbeddingService`

### Phase B — Generic scheduler
- `ScheduleDefinition`, `ScheduleLoader` (reusing TOOL.md frontmatter parser)
- `PlaceholderResolver` with golden tests for all placeholder forms
- `ScheduledTaskRunner` + `ScheduleStateRepository`
- Integration test: stub schedule with 1-sec cron → assert agent_run inserted with
  expected fields

### Phase C — Generic AWS tool (CloudWatch Logs only)
- Gradle dep: `software.amazon.awssdk:bom:2.28.x` + `cloudwatchlogs`
- `AwsConfig`, `AwsInvoker`, `CloudWatchLogsHandler`, `AwsLuaBindings`
- `.kukuvaia/tools/aws/TOOL.md` + `config.yaml`
- Integration test with LocalStack (`testcontainers-go/minio` analogue for CW Logs)
  — create log group, put events, run `start_query_and_wait`, assert results
- Security: whitelist enforcement unit test

### Phase D — End-to-end validation
- A sample schedule in `docs/examples/schedules/` (NOT in `.kukuvaia/schedules/`)
  demonstrating a full CloudWatch Logs analysis flow
- Manual smoke: run kukuvaia, drop the sample schedule into `.kukuvaia/schedules/`,
  observe agent_run rows appearing every N minutes, inspect outputs

### Phase E (follow-up, not part of v1) — Convenience layers
- CLI command `kukuvaia runs list --invoker X --limit 20`
- Admin UI tab to browse agent runs and their outputs
- Webhook entry point (`POST /api/agent-runs`) that calls the same
  `AgentRunService.createAndExecute`
- Additional AWS service handlers (S3, SSM, Secrets Manager) — one per follow-up PR
- Possibly: re-point `DaemonAgentService` to write to `agent_runs` instead of the
  never-migrated `daemon_tasks`

## Testing strategy

| Layer | Tool | Coverage |
|---|---|---|
| Repository | Testcontainers Postgres + pgvector | CRUD, similarity, filters |
| Service | JUnit + Mockito | All status transitions, tool-only, LLM-only, tool+LLM, failure paths, skip-if-empty |
| Placeholder resolver | Golden tests | All placeholder forms + edge cases (negative intervals, missing env) |
| Scheduler | Testcontainers + manual cron | Boot-time registration, one-second cron smoke |
| AWS tool | LocalStack Testcontainer | Each CloudWatch Logs op, whitelist denial, response-size truncation |

## Metrics & observability

Reuse existing Micrometer infrastructure. Add:
- `kukuvaia_agent_runs_total{invoker_kind, status, input_type}` — counter
- `kukuvaia_agent_run_duration_seconds{invoker_kind, status}` — histogram
- `kukuvaia_agent_run_tokens_total{model, field=prompt|completion}` — counter
- `kukuvaia_schedule_runs_total{schedule, status}` — counter

## Security

- `aws` tool is **not** readOnly (can call mutating AWS APIs in principle), so honours
  the existing tool-whitelist mechanism on persona level. Default personas do not get
  `aws` in their allowlist — must be explicitly granted.
- AWS credentials: SDK default provider chain. No credentials stored in kukuvaia code
  or config files. Env + IAM role only.
- Allowlist in `config.yaml` (`allowed_services`) prevents accidental expansion beyond
  intended surface. v1 ships with only `cloudwatchlogs`.
- Agent-run inputs/outputs are persisted as-is in `input` / `output` JSONB. Any invoker
  putting secrets into these fields will see them persisted — document this as a caller
  responsibility; follow-up plan can add optional field-level redaction config.

## References

- **Upstream design doc** (consuming project): `/Users/bartosz.pawlak/Projects/idb/docs/kukuvaia-integration.md`
- **Industry naming validation**: LangSmith (Runs/Traces/Threads), OpenAI Assistants API
  (Run with status lifecycle), LangGraph (Thread + checkpoints + Runs). All three
  converged on "Run" as the noun for "one execution of an agent"; `parent_run_id` is
  idiomatic for chains (LangSmith) and `thread_id` for grouping (LangGraph).
- **Related kukuvaia plans**:
  - P03 (Model fallback chain) — agent runs use the same `ChatClient` that P03
    augments with fallback; escalation in §Design / 6 is a higher-level policy layer
    that sits above P03's transport-level retries.
  - P07 (Cost Tracking) — `total_tokens` GENERATED column + `model` column on
    `agent_runs` feed directly into P07's per-request cost calculator.
    Escalation is recorded by token summation, so P07 accounting remains accurate
    per run.
  - P15 Pillar 1 (Provenance) — eventually `agent_runs.output` can carry a provenance
    block for claims derived from tool calls.
  - P18 (Intelligent task routing) — `AgentRunSpec.taskClass` hint is the integration
    point. Until P18 ships, `modelPreference.primary` is the explicit fallback.
  - P19 (Complexity-driven routing) — once shipped, will resolve `taskClass` into a
    model choice based on input complexity metrics, superseding the explicit
    `primary` field for schedules that opt into it.
  - P20 (Routing self-tuning) — agent runs produce a clean signal for P20's feedback
    loop (known invoker, known task class, token usage, escalation outcome), without
    sessions/memory noise.
- **Not related**:
  - `DaemonAgentService` / `DaemonBudgetGuard` — aspirational, untouched by this plan.
  - `MemoryExtractionService` — interactive-only, untouched.

## Decision log

| Decision | Choice | Rationale |
|---|---|---|
| Where to persist non-interactive runs | New `kukuvaia.agent_runs` table, peer to sessions | Session/memory pipeline is semantically wrong for one-shot system invocations. Industry (LangSmith/OpenAI/LangGraph) converged on "Run" as a peer concept. |
| Table naming | `agent_runs` | Direct match to LangSmith/OpenAI/LangGraph "Run" terminology. Unambiguous. |
| Actor representation | `invoker_name` (string) + `invoker_kind` (string) | No FK to users. Non-human actors should never appear in `users`. Strings are simpler than a dedicated `invokers` table — can formalize later. |
| Grouping | `thread_id` nullable + `parent_run_id` nullable | Two independent grouping mechanisms: Thread (LangGraph) for shared context, parent (LangSmith) for chain. Both nullable → zero cost when unused. |
| Embedding model | Same as `kukuvaia.memories` (384-dim all-MiniLM-L6-v2) | Enables future cross-comparison; no new model to maintain. |
| Token fields | Separate `prompt_tokens` + `completion_tokens` + generated `total_tokens` | Industry standard; matches `ProviderAuditLog` naming; enables per-leg cost tracking for P07. |
| Timing fields | Granular (`queued_at`, `started_at`, `completed_at`, `created_at`) | Matches OpenAI Run lifecycle; enables "find long-running runs" / "find stuck in queued" queries cheaply. |
| AWS SDK | v2 (`software.amazon.awssdk:*`, BOM 2.28.x) | Native fit for Java 21 / Spring Boot 3.4. AI-gen S-tier. Stable surface. |
| Scheduler config | `.kukuvaia/schedules/*.md` with YAML frontmatter | Mirrors existing `.kukuvaia/tools/**/TOOL.md` convention — declarative, PR-reviewable, no API surface. |
| V1 AWS scope | CloudWatch Logs only | Keep blast radius small; other services ship as independent small PRs when needed. |
| Session/memory mutation | None — invariant rule | Interactive flow is mature; touching it for a side-lane feature is a guaranteed regression source. |
| Model selection | Explicit `modelPreference` in schedule + optional `taskClass` hint for future P19 routing | Cost matters: log triage runs ~960×/day at scale. Small models handle this class well. Explicit config wins until P19 ships. |
| Escalation trigger language | SpEL (already a Spring dependency) | No new expression library; `output.*` / `status` context is trivial to build; malformed expressions fail-safe to no-escalation. |
| Escalation depth limit | `max-escalation-depth: 1` (configurable, default 1) | Hard cap prevents pathological loops; one escalation covers real-world need (Haiku → Sonnet). Opus-level runs trigger manually via separate schedule, not recursion. |
| Token accounting across escalations | Sum first + second pass into `total_tokens`; keep `model` = escalated model | Simpler than adding schema columns; multi-tier cost is still visible through metrics (`kukuvaia_agent_run_escalations_total`). |
| Prompt caching | Opt-in via `promptCaching: true`; provider-specific implementation | Anthropic explicit (`cache_control`), OpenAI implicit (≥1024-token prefix). Flag stays informational for OpenAI. |

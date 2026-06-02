# P14 — Tiered Context for Multi-Agent Flows

**Status:** Draft — document only, implementation deferred until a concrete pain signal appears.
**Created:** 2026-04-18

## Motivation

Today every advisor and every sub-agent sees the same message budget — the last 20 messages from `MessageWindowChatMemory`. There is no differentiation between:

- The **supervisor** (main agent orchestrating a task) — typically wants broad context.
- **Workers / sub-agents** — typically need only a focused slice relevant to their narrow task.
- **Advisors** — could operate on a minimal slice (e.g., last 2 turns) for many use cases.

This one-size-fits-all budget has two consequences:

1. **Cost** — every sub-agent call pays for the full 20-message context even when its task is scoped to a single turn.
2. **Focus** — a specialist sub-agent receiving unrelated history is more prone to attention drift and off-task reasoning.

Tiered context addresses both: assign each role a context budget and decide what to forward at delegation time.

## Current state (what already exists, what does not)

| Exists | Description | Gap |
|--------|-------------|-----|
| `SubAgentFactory` | Each sub-agent gets an isolated `ChatClient` with its own system prompt. | Does not receive a slice of parent conversation — starts fresh with only the `task` string passed via `DelegationTools.delegateToSubAgent`. |
| `ModelRoutingAdvisor` | Picks model tier per request (sonnet / haiku / opus). | Does not pick context **size** — routing is orthogonal to context budgeting. |
| `MessageWindowChatMemory` | Sliding window of last N messages (hardcoded 20). | N is global, not per-role. |
| `DelegationTools.delegateToSubAgent` | Parent LLM calls this as a tool. | Parent decides `task` string ad-hoc; there is no systemic "forward last X relevant turns" mechanism. |

## Proposed architecture (high level)

### 1. Role-based context budget

Extend `PersonaSpec` (or add a new `RoleSpec`) with a `contextBudget` field:

```
contextBudget: FULL | FOCUSED | MINIMAL
```

- `FULL` — supervisor default; full window (e.g. 50 messages + top-K memories).
- `FOCUSED` — worker default; reduced window (e.g. 5 messages + task description + targeted memory query).
- `MINIMAL` — tools / narrow advisors; task description only, no history.

### 2. Selective forwarding on delegation

When the supervisor calls `delegateToSubAgent`, a new `ContextSelector` component assembles the slice that the child will see. Three forwarding strategies to consider (to be decided when the plan is activated):

| Option | Mechanism | Pros | Cons |
|--------|-----------|------|------|
| **A — LLM-picked** | Supervisor LLM summarises relevant context before calling the tool. | Semantic awareness, handles novel cases. | Extra LLM cost, hallucination risk (important detail dropped). |
| **B — Deterministic** | Selector pulls last N turns filtered by keyword overlap with the sub-task, plus top-K from pgvector memory. | Predictable, testable, cheap. | Brittle on paraphrased references; misses implicit dependencies. |
| **C — Hybrid** | Deterministic base slice + optional LLM-generated "notes for specialist". | Covers both edge cases. | Most code, two moving parts. |

### 3. Worker delegation (downward routing)

Symmetric to P15 Pillar 4 (`consultExpert` — lateral routing to domain specialists). Supervisor delegates simple, scoped subtasks **down** to a cheap worker rather than burning the full context on trivial operations.

#### New tool — `delegateToWorker`

```java
@Tool(description = """
        Delegate a small, scoped subtask to a cheap fast worker. \
        Use for extraction, classification, formatting, simple lookups, or any step \
        where the full conversation context is not needed.""")
public Map<String, Object> delegateToWorker(
        @ToolParam(description = "Focused task description, self-contained") String task,
        @ToolParam(description = "Optional minimal context (a few sentences)") String context) {
    // Routes to the FAST tier ChatModel via ChatModelCache.getModelIdForRole("worker")
    // Uses ContextSelector with budget=MINIMAL — task + context, no conversation history.
}
```

#### When the supervisor should call it

Push into the system prompt (minimal, English, no examples per project convention):

> For narrow, self-contained subtasks — extraction, classification, formatting — delegate to `delegateToWorker` instead of answering inline. Reserve full context for reasoning the worker cannot do.

The supervisor decides per call. No heuristic classifier upstream. This is in contrast to `ModelRoutingAdvisor`, which picks a tier **for the entire turn** based on the incoming user message.

#### Relationship to existing mechanisms

| Mechanism | Decision | Scope | Who decides |
|-----------|----------|-------|-------------|
| `ModelRoutingAdvisor` | FAST / DEFAULT / ESCALATE tier | Whole turn | Heuristic (keyword + length) |
| `delegateToWorker` (new) | Send subtask to worker | Single subtask within a turn | Supervisor LLM |
| `consultExpert` (P15 Pillar 4) | Route to domain specialist with grounding tools | Single subtask | Supervisor LLM |
| `delegateToSubAgent` (existing) | General sub-agent spawn | Single subtask | Supervisor LLM |

`delegateToWorker` is a specialisation of `delegateToSubAgent` with forced `contextBudget=MINIMAL` and forced routing to the worker-tier model. Kept as a distinct tool so the supervisor has an obvious, low-friction option for "cheap subtask" without inventing sub-agent specs.

### 4. Escalation on hard cases

If a sub-agent detects it lacks context to proceed, it can return a structured `NEEDS_MORE_CONTEXT` signal that:

- Re-invokes itself with an enlarged slice (e.g., `FOCUSED` → `FULL`).
- Or requests specific information from the parent (e.g., "which outline were we discussing?").

This is an advanced feature, useful only once tiered context is live and measurable.

## Integration with sibling mechanisms

Three routing / budgeting systems operate on the same chat pipeline. They do not overlap — they compose. This section exists so that P14, P15, and `ModelRoutingAdvisor` stay coherent.

```
User turn arrives at ChatController
        │
        ▼
CommandRouter → AgentService.streamChat
        │
        │  [1] ModelRoutingAdvisor  (exists today)
        │       → picks TURN tier: FAST | DEFAULT | ESCALATE
        │       → based on keyword + length heuristic
        │
        │  [2] Advisor chain runs (SmartMemory, MessageChatMemory, PlanningMode, …)
        │
        │  [3] ToolCallAdvisor loop — supervisor LLM reasons
        │       │
        │       │  Supervisor picks one of these per subtask:
        │       │    ─ answer inline (default, full context)
        │       │    ─ delegateToWorker       ← P14 §2.3 (cheap, MINIMAL context)
        │       │    ─ consultExpert          ← P15 Pillar 4 (domain grounding)
        │       │    ─ delegateToSubAgent     ← existing (custom specialist)
        │       │
        │       │  Context size for each delegation:
        │       │    ─ ContextSelector applies contextBudget per role (P14 §1)
        │       │
        │       │  Pillar 3 (P15) may force a grounding call for
        │       │  low-calibration domains before the supervisor replies.
        │
        ▼
[For /plan only]  RED_TEAM phase fires before APPROVAL (P15 Pillar 2)
```

### What each mechanism owns

| Concern | Owned by | Decision unit | Decider |
|---------|----------|---------------|---------|
| Which model tier handles the **turn** | `ModelRoutingAdvisor` (exists) | Entire turn | Deterministic heuristic |
| How much **context** a consultee sees | P14 §1 (`contextBudget`) + P14 §2 (`ContextSelector`) | Per delegation call | Role config + selector strategy |
| When to **delegate downward** (worker) | P14 §2.3 (`delegateToWorker`) | Per subtask | Supervisor LLM |
| When to **consult lateral** (domain expert) | P15 Pillar 4 (`consultExpert`) | Per subtask | Supervisor LLM (with Pillar 3 forcing) |
| When to **verify output** (red-team) | P15 Pillar 2 (`RED_TEAM` phase) | Per plan draft | Phase transition |
| **Honesty** of output (provenance, calibration) | P15 Pillar 1 + Pillar 3 | Every assertion | Tool-level + empirical data |

### Expected evolution

- Short term: `ModelRoutingAdvisor` stays as the only active piece. The three delegation tools (`delegateToWorker`, `consultExpert`, `delegateToSubAgent`) come online as P14 §2 and P15 Pillar 4 activate.
- Medium term: supervisor-LLM-driven delegation replaces most heuristic turn-level routing. `ModelRoutingAdvisor` falls back to "pick sensible default tier" — the interesting decisions happen inside the tool-call loop.
- Long term: calibration-driven forcing (P15 Pillar 5) removes the remaining reliance on the supervisor noticing its own uncertainty.

## Small-supervisor mode (Villani-inspired)

Cross-referenced with `/tmp/villani-code` research (2026-04-20). Villani's benchmark (Qwen 27B > Claude Code) shows that **runtime discipline beats raw capacity** — the win comes from bounded context, signal-token compaction, and pressure-level monitoring, not from a bigger model. Kukuvaia should be able to run with a 7B–27B self-hosted supervisor; this section defines what changes when the supervisor tier is `SMALL`.

### Config knob

```yaml
kukuvaia:
  supervisor:
    # LARGE (default) — full proactivity rules, 20-message window, verbose prompt
    # SMALL           — concise prompt, tighter budgets, signal-token compaction enforced
    verbosity: ${KUKUVAIA_SUPERVISOR_VERBOSITY:FULL}   # FULL | CONCISE
  context:
    # Hard budget (tokens) at which ContextPressureAdvisor starts shedding low-priority items.
    budget-tokens: ${KUKUVAIA_CONTEXT_BUDGET:35000}
    pressure-thresholds:
      moderate: 0.45   # start emitting metrics
      high:     0.75   # start pruning stale items
      overflow: 1.00   # force-prune FIFO
```

### System prompt variant

`PersonaService` picks a prompt based on `kukuvaia.supervisor.verbosity`. Both variants live in code (not YAML) because they are stack-critical defaults.

- `FULL` — current prompt (proactivity rules, ambiguity discipline, Session Context usage).
- `CONCISE` — Villani-style bounded discipline, <60 words, imperative, English:

  > You are Kukuvaia. Use tools for every factual or action-taking step; never assert results without a tool. Before editing, name the likely target. Prefer minimal changes. Ask one short clarifying question when intent is ambiguous. Say "unknown" rather than invent.

  Proactivity (unfinished plans, commitments) moves from prompt to code — `SessionContextAdvisor` injects structured prompts only when such state exists, so the small model does not carry always-on instructions it will forget.

### Context pressure levels

Villani's `ContextPressureLevel` adapted as a new `ContextPressureAdvisor` (before `MessageChatMemoryAdvisor` in the chain):

| Level | Threshold | Behaviour |
|---|---|---|
| `LOW` | <45% budget | No-op. Emit `kukuvaia.context.usage` gauge. |
| `MODERATE` | 45–75% | Start compacting new tool outputs (signal-token filter). Emit warning counter. |
| `HIGH` | 75–100% | Prune stale items: repair attempts ≥ 2, oldest tool results with `PASS` outcome, duplicated memory hits. |
| `OVERFLOW_RISK` | >100% | FIFO-prune active items until back under budget. Log structured event with `pruned_count`. |

Composes with P04 (conversation summarisation) — summarisation happens first, pressure-driven pruning is the safety net.

### Signal-token compaction

When `verbosity=CONCISE` or pressure ≥ `MODERATE`, tool outputs going into the next turn's context are filtered line-by-line keeping only lines containing signal tokens:

- Shell / bash: `error`, `failed`, `exit`, `warning`, `traceback`
- Test runs: `FAILED`, `PASSED`, `error`, `assertion`, `at line`
- SQL: `ERROR`, `WARNING`, `rows affected`
- Repo reads: first 40 lines + last 20 lines + lines matching the query

Full output is still persisted (`conversations` JSONB) — compaction is applied only to the **prompt projection**, so debug never loses data.

### Tiered budgets for small supervisor

When `verbosity=CONCISE`:

| Role | `FULL` budget | `CONCISE` budget | Rationale |
|---|---|---|---|
| Supervisor | 50 msgs | **10 msgs** | 7B/13B models drift past ~10 turns. |
| Worker | 5 msgs | **3 msgs** | Already scoped; shrink further to save prompt. |
| Minimal | 0 | 0 | Unchanged. |

### Stale context detection

Villani pattern — if `repair_attempts ≥ 2` and active items > N, we are in a loop. Signal from `ContextPressureAdvisor`:

- If the same tool call (by name + argument hash) returns a failure outcome for the third time in a row → inject `"Loop detected — change approach or escalate."` into next turn and emit `kukuvaia.context.stale_loop_detected` counter.
- If 3 consecutive turns produce no edit and supervisor is in `SMALL` mode → force escalate (raise `verbosity` back to `FULL` for one turn, or hand off to larger model via P19 routing).

### Relationship to existing plans

- `ModelRoutingAdvisor` picks the tier per turn (FAST / DEFAULT / ESCALATE) — does not know about pressure.
- New `ContextPressureAdvisor` runs every turn, regardless of tier. It informs pruning, not routing.
- P19 (complexity-driven routing) can consume pressure signals as an additional feature at activation time.

### Additional determinism patterns (Villani R2)

Second-pass research of Villani (2026-04-20, `/tmp/villani-code`) surfaced three more patterns worth adopting. All three push decisions from LLM calls to deterministic code, which compounds with the `CONCISE` prompt — small models benefit doubly (shorter prompt + fewer judgement calls to make).

#### Atomic message units during compaction

Pressure-driven pruning must not split a `[tool_use → tool_result]` pair. Villani reference: `context_budget.py:82-93`. A dropped tool_result with a kept tool_use confuses every subsequent turn because the model sees a phantom request.

Rule for `ContextPressureAdvisor.prune()`:

- Group messages into atomic units: assistant message with `tool_calls` + its matching user-role `tool` responses form one unit.
- Drop or keep whole units, never partial.
- A unit's priority = max priority of its members (so important tool results protect their assistant turn).

Java-side hook: extend `MessageWindowChatMemory` wrapper to iterate by `AtomicMessageUnit` list, not by `Message`.

#### Deterministic turn summarisation

Instead of calling an LLM to summarise old turns (P04 default path), extract structural fields via regex / parsing. Villani reference: `context_budget.py:128-193`.

Fields extracted per turn:

- **objectives** — lines the user wrote beginning with `want`, `need`, `please`, or containing `?`.
- **files_read** — tool_result blocks where the tool name is `Read`, `Grep`, `Glob`.
- **edits** — tool_result blocks where the tool name is `Write`, `Patch`, `Edit`.
- **validations** — tool_result blocks where the tool name is `Bash` and stdout contains test/validation tokens.
- **blockers** — tool_result blocks with exit code != 0 or containing `error`, `failed`, `traceback`.

Output format injected at the head after compaction:

```
[Earlier turns summary]
Objectives: …
Files read: src/foo.java, test/FooTest.java
Edits: src/foo.java (+12, −3)
Validations: FooTest passed (8/8)
Blockers: none
```

Pure string operations, no LLM call. Composes with P04 — deterministic summary is the default, P04's LLM-based summary kicks in only when `--rich-summary` is requested or when deterministic output is judged too sparse.

#### Category state tracking

Villani reference: `autonomous_progress.py:10-37`. An autonomous agent tracks a small map of artefact categories (tests, docs, entrypoints, imports) with states `unknown | discovered | attempted | exhausted`. The stop decision is an enum-switch on the tuple, not a question to the LLM.

For kukuvaia, extend `MissionState` (used by P15 Pillar 5 and autonomous workflows) with:

```java
public record CategoryState(
        String category,       // tests, docs, entrypoints, imports, configs, …
        Stage stage,           // UNKNOWN, DISCOVERED, ATTEMPTED, EXHAUSTED
        int attempts
) {
    public enum Stage { UNKNOWN, DISCOVERED, ATTEMPTED, EXHAUSTED }
}
```

Updated deterministically after each turn: tool result with `Read src/…/Test…java` moves `tests` from UNKNOWN to DISCOVERED; a `Bash` running that test moves it to ATTEMPTED; three ATTEMPTED + still failing → EXHAUSTED.

Stop decision: `if categories.stream().allMatch(c -> c.stage == EXHAUSTED || c.stage == ATTEMPTED) && confidence < 0.5 → STOP`. No LLM ask-yourself-if-you're-done call.

### Villani reference values (anchors for defaults)

Concrete numbers from `/tmp/villani-code/villani_code/execution.py:37-43` and related — useful as starting defaults when activating phases, not as gospel:

| Limit | Villani value | Our file | Notes |
|---|---|---|---|
| `max_turns` | 20 | `execution.py:37` | Autonomous wave only |
| `max_tool_calls` | 40 | `execution.py:38` | Per task |
| `max_seconds` | 180 | `execution.py:39` | Per task |
| `max_no_edit_turns` | 8 | `execution.py:40` | Loop guard — no edit for 8 turns → stop |
| `max_consecutive_recon_turns` | 6 | `execution.py:41` | Too much inspection without action → stop |
| Small-model context chars | 35000 | `state.py:504` | ~8.7K tokens |
| Small-model keep_last_turns | 4 | `state.py:505` | |
| `Read.max_bytes` | 50000 | `state_runtime.py:592` | |
| `Grep.max_results` | 60 | `state_runtime.py:593` | |
| Bash stdout truncation | >6000 → first 2K + last 3K | `state_runtime.py:598` | |

### Small-model readiness — external reference

Villani's benchmark (Qwen 4B-27B) and design suggest small models are ready **only when**:

- Model has reliable structured tool-use (rules out GPT-2-style, some early 4B models).
- Model is instruction-following (Qwen 32B ✅, Llama 3.1 70B ✅, Mistral 7B borderline, Phi-3 ❌ on complex instruction chains).
- Caller accepts the budget discipline (hard turn/tool/time caps, scope locks, read-before-edit).

For kukuvaia, auto-activate `verbosity=CONCISE` when `ModelRoutingAdvisor.activeTier == FAST` or `context_window < 50_000` tokens — two cheap signals that correlate with the model tier.

---

## Implementation phases (when triggered)

1. **Phase 1 — Budget plumbing**
   - Add `contextBudget` to `PersonaSpec`.
   - Teach `MessageChatMemoryAdvisor` (or a new budget-aware wrapper) to look up the active role's budget and size the window accordingly.
   - Defaults: supervisor = 50, worker = 5, minimal = 0.

2. **Phase 2 — Selective forwarding + worker delegation**
   - Implement `ContextSelector` with strategy A, B, or C (chosen at activation time).
   - Wire into `DelegationTools.delegateToSubAgent` — the `task` string is augmented with the selected slice.
   - Add `DelegationTools.delegateToWorker(task, context)` tool — forced `contextBudget=MINIMAL`, routed to the worker-tier model via `ChatModelCache.getModelIdForRole("worker")`.
   - Update default persona system prompt with the one-line instruction: delegate narrow subtasks to `delegateToWorker`.

3. **Phase 3 — Escalation protocol**
   - Define `NEEDS_MORE_CONTEXT` return schema for sub-agents.
   - Re-invocation handler in `SubAgentFactory` or `DelegationTools`.

## Triggers for activation

Do **not** start implementation until one of the following signals appears:

- Monthly LLM cost > some threshold, with sub-agent calls measurably dominating spend.
- Recurring quality regression where sub-agents reason on unrelated context (tracked in `docs/fix/` incident reports).
- A specific multi-agent workflow being designed where the performance / cost difference between tiered and flat context is material.

Until then, leave the one-size budget (`maxMessages=20`) in place.

## Risks

| Risk | Mitigation |
|------|------------|
| Over-filtering — important context dropped | Start with generous budgets (supervisor 50, worker 10); measure before shrinking. |
| Under-filtering — no cost benefit | Track per-role token usage via existing `ProviderAuditLog` before and after Phase 1. |
| LLM-picked forwarding hallucinates | Prefer deterministic base (Option B) first; add LLM summarisation (Option C) only if needed. |
| Debug complexity | Log the exact selected slice at DEBUG level; expose via `/api/sessions/{id}/planning`-style observability endpoint. |

## Rollback

All changes are additive. Reverting = remove `contextBudget` field, delete `ContextSelector`, keep the global `maxMessages`. No DB schema change required for Phase 1.

## Related work

- `docs/work/005-conversation-summarization/plan.md` — complementary: summarises old turns rather than dropping them. Tiered context and summarisation compose (supervisor gets summary + recent turns; worker gets only relevant slice).
- `docs/architecture/agent-orchestration-decisions.md` — sub-agent as `@Tool` design that this plan extends.
- `docs/architecture/chat-flow.md` — the pipeline this plan modifies.

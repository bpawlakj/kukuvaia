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

- `docs/plan/P04-conversation-summarization.md` — complementary: summarises old turns rather than dropping them. Tiered context and summarisation compose (supervisor gets summary + recent turns; worker gets only relevant slice).
- `docs/architecture/agent-orchestration-decisions.md` — sub-agent as `@Tool` design that this plan extends.
- `docs/architecture/chat-flow.md` — the pipeline this plan modifies.

# P13 — Planning DISCOVERY: Structured State & Observability

**Status:** Draft
**Created:** 2026-04-17
**Supersedes:** prompt-only fix in `PlanningModeService.buildPhasePrompt()` (retained as fallback)

## Motivation

Current DISCOVERY phase relies on the LLM to mentally track KNOWN / EXCLUDED / GAPS across turns. Problems:

1. **Prompt bloat** — 40-line meta-prompt injected every turn.
2. **Drift** — LLM re-derives facts from chat history each turn; long conversations lose context.
3. **No observability** — cannot inspect what the agent "understood" mid-discovery.
4. **Not testable** — deterministic assertions impossible on pure prompt behavior.

Option B replaces LLM-internal tracking with **system-managed structured state**, persisted per session and rendered into each prompt as compact CURRENT STATE.

## Data Model

### `DiscoveryFacts` (new record)

```java
public record DiscoveryFacts(
    List<String> knownFacts,       // things user stated or confirmed
    List<String> excludedOptions,  // things user ruled out
    List<String> remainingGaps     // what still needs answers
) {
    public static DiscoveryFacts empty() {
        return new DiscoveryFacts(List.of(), List.of(), List.of());
    }
}
```

### `PlanningSession` (extend)

```java
public record PlanningSession(
    String task,
    PlanningPhase phase,
    Instant startedAt,
    DiscoveryFacts facts   // NEW — mutable-by-replacement
) {
    public PlanningSession withFacts(DiscoveryFacts newFacts) { ... }
}
```

## State Update Strategy

**Chosen:** **LLM tool call `updateDiscoveryFacts` during DISCOVERY**.

LLM reads current state (injected by advisor), decides if any fact changed after user's message, calls tool to update. No extra round-trip in the common case (tool call is part of the same response).

Alternatives considered:
- **Separate extraction call before each reply** — doubles latency, 2x cost.
- **One-shot extraction on `/plan` entry** — no evolution as user clarifies.
- **Structured output (`.entity(DiscoveryFacts.class)`)** — conflicts with streaming SSE responses.

Tool-call approach reuses existing tool infrastructure, plays well with streaming, LLM already knows the pattern.

## Advisor Changes (`PlanningModeService`)

### BLOCKED_IN_DISCOVERY (update)
```java
private static final Set<String> BLOCKED_IN_DISCOVERY = Set.of(
    "createPlan", "revisePlan", "completeStep"  // updateDiscoveryFacts allowed
);
```

### DISCOVERY prompt (shrink from 40 → ~12 lines)
```
## Planning Mode — Discovery Phase
Task: "{task}"

### Current state (system-managed)
Known: {facts.knownFacts, comma-sep, or "none yet"}
Excluded: {facts.excludedOptions or "none yet"}
Remaining gaps: {facts.remainingGaps or "to be determined"}

### Your job
1. Call updateDiscoveryFacts when the user's message reveals new facts, exclusions, or resolves gaps.
2. Ask 2-3 focused questions ONLY about "Remaining gaps". Never ask about "Excluded" items.
3. When gaps are empty OR user signals readiness, summarize and ask them to type 'ready'.

Do NOT call createPlan yet. Respond in the user's language.
```

### New tool (`PlanningTools.updateDiscoveryFacts`)
```java
@Tool(description = "Update the discovery fact list. Call whenever the user's message reveals new facts, exclusions, or answers a gap.")
public Map<String, Object> updateDiscoveryFacts(
    @ToolParam(description = "Updated list of known facts") List<String> knownFacts,
    @ToolParam(description = "Updated list of excluded options") List<String> excludedOptions,
    @ToolParam(description = "Updated list of remaining gaps") List<String> remainingGaps) {
    // Replaces session.facts via planningModeService.updateFacts(sessionId, ...)
    // Returns the applied state.
}
```

## Initial Extraction

When `startPlanning()` is called with a non-trivial task description, run one synchronous extraction call:

- ChatClient call with system prompt: "Extract planning facts from this task. Return JSON matching DiscoveryFacts schema."
- Spring AI `.entity(DiscoveryFacts.class)`
- Populate `PlanningSession.facts` before first user-visible turn.

Timeout: 5s. On failure → empty facts, LLM builds them via tool call in first turn. Logged as WARN.

## Observability

### Logs (structured)
- `INFO` on every `updateDiscoveryFacts` call: delta vs previous state, sessionId.
- `INFO` on phase transition with fact snapshot.
- `WARN` on extraction failure.

### API endpoint (new)
```
GET /api/sessions/{sessionId}/planning
→ 200 { task, phase, startedAt, facts: { knownFacts, excludedOptions, remainingGaps } }
→ 404 if not in planning mode
```

Added to `SessionController` (no new controller needed).

### Future CLI hook
CLI can poll this endpoint during planning to render a "What I understood" panel. Out of scope for this plan — unblocked by endpoint existence.

## Cleanup / Hygiene

- `ConcurrentHashMap<String, PlanningSession>` still has no TTL. Out of scope, noted for future.
- `READY_TRIGGERS` list unchanged.

## Implementation Checklist

- [ ] `DiscoveryFacts.java` — new record
- [ ] `PlanningSession.java` — add facts field + `withFacts()`
- [ ] `PlanningModeService.java`:
  - [ ] `updateFacts(sessionId, DiscoveryFacts)` method
  - [ ] Shrink DISCOVERY prompt, inject current state
  - [ ] Remove `updateDiscoveryFacts` from BLOCKED_IN_DISCOVERY (it's not listed, just confirming allowlist)
  - [ ] Optional: extraction on `startPlanning()`
- [ ] `PlanningTools.java` — add `updateDiscoveryFacts` tool
- [ ] `SessionController.java` — new `GET /api/sessions/{id}/planning` endpoint
- [ ] Unit tests: `PlanningModeServiceTest` for fact state transitions
- [ ] Integration: manual test `/plan wyjazd na Krete, jedziemy prywatnym samochodem`

## Risks

| Risk | Mitigation |
|------|------------|
| LLM doesn't call `updateDiscoveryFacts` | Prompt instruction is explicit; fallback prompt directive retained |
| Extraction returns bad JSON | Spring AI `.entity()` handles; retry once; empty fallback |
| Facts list grows unbounded | Tool description caps at ~5-7 items per list |
| Session leak in HashMap | Existing issue, not introduced here; track separately |

## Rollback

Pure additive:
- Remove tool from `PlanningTools`
- Revert `buildPhasePrompt` to prompt-only version
- `DiscoveryFacts.empty()` remains a no-op in `PlanningSession`

Zero DB schema changes — no migration to rollback.

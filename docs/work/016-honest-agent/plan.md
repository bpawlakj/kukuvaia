# P15 — Honest Agent: Provenance, Calibration, Red-Team, Expert Marketplace

**Status:** Draft — document only, phased activation when each pillar has a trigger.
**Created:** 2026-04-18
**Positioning:** _"Kukuvaia is not smarter — it is more honest. You see what is verified, what is not, and why."_

## Motivation

Competitors (ChatGPT, Claude, Cursor, LangChain-based agents) either hide grounding ("one big answer") or expose only low-level building blocks. None give the user a **transparent trust model**: which claims are verified, which are model-only, which failed peer review. None track **empirical calibration** of their own reasoning over time. Self-hosted extensibility (own experts, own data) is rare or locked inside proprietary platforms.

Kukuvaia already has the ingredients to ship a differentiated "honest agent" experience:

- Structured `OutputBlock` hierarchy (fields are addable).
- Planning state machine with explicit phases and observable state.
- Multi-provider LLM routing, pgvector memory, personas, `.kukuvaia/` extension loader.

This plan groups four pillars that together define the differentiator.

---

## Pillar 1 — Provenance Ledger

Every factual assertion in agent output carries a source and a confidence marker. The CLI and admin UI render these visibly.

### Data model

Extend each `OutputBlock` subtype with an optional list of provenance tags:

```java
public record Provenance(
    SourceKind kind,   // WEB, DATABASE, TOOL, MEMORY, MODEL_ONLY
    String ref,        // URL, table:id, tool name, memory id, or null
    Confidence level   // VERIFIED, LIKELY, UNVERIFIED
) {}
```

`TextBlock`, `TableBlock`, `CodeBlock`, `SectionBlock` gain `List<Provenance> sources`.

### Rendering

- CLI: green / yellow / red indicator per block or inline per claim.
- Admin UI: expandable "sources" panel per block.

### Capture points

- Every `@Tool` call emits a `Provenance(TOOL, toolName, VERIFIED)` on its result.
- `SmartMemoryAdvisor` emits `Provenance(MEMORY, memoryId, LIKELY)` when injecting.
- Web search tool emits `Provenance(WEB, url, VERIFIED)`.
- Any assertion the LLM makes without a backing tool call is `Provenance(MODEL_ONLY, null, UNVERIFIED)`.

### Tradeoff

Adding provenance requires tool authors and the planner prompt to be disciplined. Uncaptured claims fall back to `MODEL_ONLY` — that is the honest default, not a bug.

---

## Pillar 2 — Red-Team Verification Phase

After DRAFTING, run a separate adversarial pass before APPROVAL. The pass does
two things in one sweep: **silently auto-corrects mechanical defects**, and
**surfaces judgment calls as recommendations** for the user. Purely adversarial
review without auto-correct would burn the user's attention on things the
engine should just fix itself.

### Mechanism

- New `PlanningPhase.RED_TEAM` inserted between `DRAFTING` and `APPROVAL` (optional, gated by config or persona).
- Adversarial prompt: _"Find weaknesses, factual errors, and risky assumptions in this plan. For each, state severity (AUTO_FIX / RECOMMEND / INFO) and a concrete correction."_
- Run by a different model (or the same model with a distinct system prompt; configurable per persona). Default: supervisor-tier with adversarial prompt — escalate to advisor-tier only when `persona.reviewTier=advisor` or `/plan --strict`.
- Structured output: `List<Finding>` where each finding has `{severity, targetStepIndex?, description, suggestedCorrection}`.
- Findings dispatched through a strict enum-switch dispatcher — never `eval`'d, never reflected.
- Output attached to the plan as a `ReviewBlock` (new OutputBlock subtype) or as a field on the plan row in `kukuvaia.plans`.

### Finding severity — auto-correction vs recommendation

| Severity | What qualifies | How the engine handles it | What the user sees |
|---|---|---|---|
| `AUTO_FIX` | Mechanical defect: step contradicts `knownFacts` / `excludedOptions`, duplicate steps, missing prerequisite step, broken ordering, invalid tool reference | Engine rewrites the plan silently via a second DRAFTING pass constrained by the finding. Logged to `plan_revisions` audit table. New step carries `Provenance(TOOL, "red-team-auto-fix", VERIFIED)` (composes with Pillar 1). | Collapsed "plan was revised — 2 mechanical issues fixed" banner; expandable to see the diff. No decision required. |
| `RECOMMEND` | Judgment call: uncertain assumption, optional optimisation, unaddressed risk, missing fallback branch | Never rewrites silently. Presented in APPROVAL panel with a checkbox per recommendation — user picks which to apply, engine does one more revision pass with the chosen set. | Panel with checkboxes. User controls scope. |
| `INFO` | Context-only: trade-off note, alternative approach the user did not ask about, out-of-scope observation | Not shown to the user. Written to the plan's audit trail + `ReviewBlock.info` field (visible in admin UI, not CLI). | Nothing. Surfaces only in reports / Dreaming. |

**Guard rails against runaway loops:**
- **Max 1 AUTO_FIX iteration per plan.** If a second RED_TEAM pass on the auto-fixed plan produces more AUTO_FIX findings, they are downgraded to RECOMMEND automatically. Prevents endless "fix A, now B is broken, fix B, now A is broken".
- **AUTO_FIX must not violate `knownFacts` or `excludedOptions`.** A finding whose suggested correction contradicts a hard constraint is rejected with a warning log — it is by definition a judgment call, not a mechanical fix.
- **Budget cap per persona** — config `kukuvaia.planning.red-team.max-auto-fixes: 3`. Beyond that, remaining AUTO_FIX become RECOMMEND so the user stays in the loop.
- **Dry-run flag** — `/plan --no-auto-fix` disables the silent rewrite path, everything surfaces as RECOMMEND. For users who want full visibility.

### User experience

When APPROVAL is presented, the user sees:

- The (possibly auto-revised) plan.
- If auto-fixes happened: a small "plan was revised — N mechanical issues fixed" banner, collapsed diff on expand.
- A collapsed "M recommendations" panel — checkbox per RECOMMEND finding. Apply selected → one more revision pass, back to APPROVAL.
- Options: approve, revise with selected recommendations, request another red-team pass, or reject and return to DRAFTING.

### Why this differs from self-reflection

Self-reflection on the same context rarely catches errors. A separate adversarial pass with a fresh prompt and (ideally) a different model is measurably better at finding issues. Closer to peer review than to introspection.

The AUTO_FIX vs RECOMMEND split is the honest-agent choice: the engine takes responsibility for correctness (mechanical issues it is certain about), leaves judgment with the user (trade-offs, risk tolerance, optimisation priorities). Neither silent "I made it better, trust me" nor maximalist "here are 12 issues, decide everything".

### Integration with other pillars

- **Pillar 1 (Provenance)** — every AUTO_FIX step carries `Provenance(TOOL, "red-team-auto-fix", VERIFIED)`. Users can distinguish user-authored from engine-corrected steps.
- **Pillar 3 (Calibration)** — RED_TEAM findings that the user accepts become `CONFIRMED` calibration events for the reviewer model; dismissed RECOMMENDs become `CORRECTED`. Over time, the reviewer itself gets calibrated per domain.
- **P20 (Routing Self-Tuning)** — thumbs-down on a reviewed plan is a signal that the review was too aggressive (or too passive); `RoutingAuditTask` can propose tier changes for the reviewer role.

---

## Pillar 3 — Empirical Calibration

Stop trusting self-reported LLM confidence ("I am sure") — measure calibration from history.

### Data captured per turn

- Domain category (geography, dates, prices, legal, code, general) — classified by a cheap model or keyword rule.
- Asserted confidence (if captured — e.g., `MODEL_ONLY` vs backed by tool).
- Outcome signal: did the user correct the claim? Did a subsequent fact-check confirm / contradict?

Schema (new Flyway migration):

```sql
CREATE TABLE kukuvaia.calibration_events (
    id              BIGSERIAL PRIMARY KEY,
    session_id      TEXT NOT NULL,
    model_id        TEXT NOT NULL,
    domain          TEXT NOT NULL,
    asserted_confidence TEXT,        -- VERIFIED / LIKELY / UNVERIFIED
    outcome         TEXT,            -- CONFIRMED / CORRECTED / UNKNOWN
    captured_at     TIMESTAMPTZ DEFAULT NOW()
);
```

### Feedback loops

- **User correction** ("actually, Crete is an island") — engine recognises correction, logs `CORRECTED`.
- **Fact-check agreement** — if a subsequent tool call contradicts a prior model claim, logs `CORRECTED`.
- **No correction within N turns** — logs `CONFIRMED` (weak signal).

### Usage

- Roll up to per-model per-domain calibration curves.
- When the supervisor writes a factual claim in a domain where the active model has historically low calibration, **force grounding** — the advisor chain mandates a tool call before the response returns.
- Admin UI dashboard: heatmap model × domain × calibration.

### Honest caveat

Calibration data is meaningful only after many turns. For a new deployment, fall back to hard-rule domain grounding (always check geography, legal, prices, dates) until data accumulates.

---

## Pillar 4 — Pluggable Expert Marketplace

Users define domain experts as YAML + tool set in `.kukuvaia/experts/`. The supervisor consults the right expert based on the domain signal.

### Structure

```
.kukuvaia/experts/
├── geography-expert.yaml
├── legal-expert-pl.yaml
├── finance-expert.yaml
└── ...
```

Each YAML:

```yaml
name: geography-expert
description: Answers geographic questions with OSM-backed grounding.
trigger_domains: [geography, travel, location]
system_prompt: >
  You are a geography expert. Always ground every spatial claim with a tool.
tools:
  - openstreetmap_query
  - elevation_api
  - distance_calc
model: haiku           # cheaper model + good tools beats expensive model alone
max_tokens: 4096
```

### Supervisor integration

- `DelegationTools.consultExpert(domain, question, context)` — routes to the expert whose `trigger_domains` matches.
- Expert runs in an isolated `ChatClient` (already supported via `SubAgentFactory`).
- Expert response returns with provenance (Pillar 1 captures it).

### Community surface

- Repo `kukuvaia-experts` on GitHub — community contributions.
- Built-in CLI command `/expert install <name>` that pulls from the repo.
- Local overrides always win — enterprise users stay air-gapped if they want.

### Why this differs from plain sub-agents

- Discovery: user does not hardcode which sub-agent to call; supervisor picks by domain tag.
- Composition: experts ship with **their own tools**, not just prompts. A small model with a correct tool beats a big model without one.
- Ecosystem: open-source + self-hosted makes this the natural home for community-maintained domain knowledge.

---

## Pillar 5 — Post-Execution VerificationEngine

Cross-referenced from `/tmp/villani-code` research (2026-04-20). Villani's core insight — and the reason Qwen 27B outperforms Claude Code on their benchmark — is that **the model that proposes an action cannot also be the authority that confirms it succeeded**. Pillar 2 (RED_TEAM) already covers plan drafting. This pillar covers the other, larger surface area: **every turn where the agent takes an action with side effects** (tool call, patch, bash, database write).

Kukuvaia today lets the LLM self-report success — "I updated the file", "the test passes" — and the supervisor continues on that basis. Pillar 5 interposes a detached verifier between action and acknowledgement.

### Mechanism

New `VerificationEngine` service + `@Tool("verify_action")`:

```java
public record VerificationResult(
        Status status,                 // PASS | FAIL | UNCERTAIN | REPAIRED
        double confidence,             // 0.0 - 1.0
        List<Finding> findings,
        String fingerprint             // hash over finding categories + refs
) {}

public record Finding(
        FindingCategory category,
        Severity severity,             // HIGH | MEDIUM | LOW
        String description,
        String evidenceRef             // pointer to tool output / shell log / diff
) {}

public sealed interface FindingCategory {
    record Regression() implements FindingCategory {}            // tests that passed now fail
    record IncompleteEdit() implements FindingCategory {}        // target file not modified though claimed
    record BrokenReference() implements FindingCategory {}       // symbol/path referenced but missing
    record StaleDoc() implements FindingCategory {}              // TODO/FIXME/outdated comment remains
    record InconsistentNaming() implements FindingCategory {}    // mixed conventions in the patch
    record HiddenSideEffect() implements FindingCategory {}      // files changed outside declared scope
    record FailedAssumption() implements FindingCategory {}      // expected state differs from actual
    record TestGap() implements FindingCategory {}               // new code path without test
    record SuspiciousBreadth() implements FindingCategory {}     // > N files changed in one action
}
```

### Confidence scoring

Villani's algorithm, adapted:

| Signal | Adjustment |
|---|---|
| Start | 0.9 |
| HIGH-severity finding | −0.18 each |
| MEDIUM-severity finding | −0.10 each |
| LOW-severity finding | −0.05 each |
| Command/test failure observed in evidence | −0.12 each |
| `SuspiciousBreadth` (>8 files) | −0.06 |
| No evidence collected (nothing verifiable) | −0.08 |
| Repeated fingerprint (same findings as previous turn) | −0.03 |

Final status:
- `PASS` — no findings.
- `FAIL` — any HIGH-severity finding.
- `UNCERTAIN` — only MEDIUM/LOW.
- `REPAIRED` — previous turn was `FAIL`, this turn resolves the finding fingerprint.

### Fingerprinting and loop detection

`fingerprint = sha256(sorted(finding.category + finding.evidenceRef))`. If the same fingerprint appears 3 turns in a row, the advisor emits a structured `stuck` signal — consumed by the UI (warn the user) and by P14 `ContextPressureAdvisor` (consider escalating to a larger model or raising verbosity).

This closes a class of failure mode common with smaller models: "I fixed it" → next turn "I fixed it" → next turn "I fixed it" — all three turns producing identical failing state.

### Trigger points (where the advisor fires)

Unlike Pillar 2 which only fires inside `/plan`, Pillar 5 fires in normal chat whenever an action has observable side effects. The advisor chain watches `ToolCallAdvisor` results and, for tools tagged `hasSideEffect`, runs verification before returning the turn:

| Tool category | Verifier runs | Evidence collected |
|---|---|---|
| Patch / write_file | Always | Diff before/after, git status, referenced symbols still resolve |
| Bash / command execution | When exit code ≠ 0 or output contains error tokens | Stdout/stderr, exit code, subsequent git status |
| DB write (`@McpTool` marked mutating) | Always | Pre/post row count, returning clause |
| Memory write | Never (not verifiable post-hoc — trust the write path) | N/A |
| Read-only tool | Never | N/A |

### Config

```yaml
kukuvaia:
  verification:
    enabled: ${KUKUVAIA_VERIFICATION_ENABLED:true}
    # Block turn on FAIL status (advisor interrupts response, asks supervisor to repair).
    # When false, FAIL only surfaces as a warning ReviewBlock — user sees the concern but flow continues.
    block-on-fail: ${KUKUVAIA_VERIFICATION_BLOCK:false}
    # Max confidence decrement before auto-escalating to a larger tier via P19.
    auto-escalate-below: 0.5
    suspicious-breadth-threshold: 8
```

Defaults keep verification observational (warn, don't block) until operators have seen enough data to trust it. `block-on-fail=true` is the endpoint of the journey — once calibration data (Pillar 3) shows the verifier itself is well-calibrated per domain.

### Relationship to other pillars

- **Pillar 1 (Provenance)** — `VerificationResult` is itself a `Provenance(TOOL, "verify_action", VERIFIED)` on the post-action claim. Users see green/yellow/red per action, not only per fact.
- **Pillar 2 (RED_TEAM)** — Pillar 2 is POST-HOC on plan _text_, Pillar 5 is POST-HOC on plan _execution_. Complementary, never overlap.
- **Pillar 3 (Calibration)** — every verification outcome (and any subsequent user override of that outcome) becomes a calibration event, so the verifier itself gets calibrated per model / domain.
- **Pillar 4 (Experts)** — domain-specific experts can ship their own verifier tools (e.g., a Terraform expert ships a `terraform plan` verifier).

### Why separate from existing `hooks.py`-style tool post-processing

Spring AI advisors already let us hook tool results, but they run **inside the ChatClient reasoning loop** — the supervisor sees hook results and can rationalise past them. `VerificationEngine` runs as a peer, produces a `VerificationResult` block that is **attached to the output, not fed back into the supervisor**. The user sees it alongside the agent's answer; the agent cannot revise the verifier's view of reality. That separation is what makes the honesty guarantee work.

### Additional deterministic mechanisms (Villani R2)

Second-pass research (2026-04-20) pulled six more mechanisms that extend Pillar 5 while keeping decisions out of the LLM:

#### Before/after content snapshots

Villani reference: `autonomy.py:99-126`, `mission_state.py:50-51`. Before every side-effecting tool call (`Patch`, `Write`, DB mutation), capture the target's current state. After the call, diff. If the expected change didn't land → emit `FailedAssumption` finding automatically, no LLM judgement.

Implementation hook: `ToolCallAdvisor` intercepts mutating tool calls, runs `ReadBeforeEditGuard`, stores snapshot in `ExecutionContext.beforeContents: Map<Path, String>`. `VerificationEngine.verify()` consumes this map.

Impossible to do post-hoc without the snapshot — must be captured pre-call. Small cost (one extra read per edit) pays for itself on every false-success turn it catches.

#### Task lifecycle state machine

Villani reference: `autonomous.py:53-61, 516-520`. A `LineageTask` moves through a fixed state machine:

```
PENDING → RUNNING → (PASSED | FAILED | BLOCKED | RETRYABLE)
RETRYABLE → RUNNING (max N times) → EXHAUSTED
```

Key invariant: each state has a `terminalFingerprint = sha256(sorted(finding.category + evidenceRef))` at the moment of transition. If a task transitions `FAILED → RETRYABLE → FAILED` and the fingerprint is identical → forced transition to `EXHAUSTED`, no further retries allowed.

Java mapping: `kukuvaia.tasks.LineageTaskState` sealed interface with strict enum-switch transitions. Prevents the "retry retry retry with same inputs" failure mode common in small-model autonomous runs.

#### Repair engine with prior-attempts context

Villani reference: `repair.py:38-106`. When repairing a failure, the repair prompt **contains the history of prior attempts** so the model cannot silently repeat the same fix:

```
Prior repair attempts:
1. failing_step="run tests" → failure="ImportError: no module foo"
   repair_summary="added import foo at top of bar.py"
   status=FAILED (same ImportError)
2. failing_step="run tests" → failure="ImportError: no module foo"
   repair_summary="created empty foo.py in project root"
   status=FAILED (PYTHONPATH issue)
```

Combined with the 13-category classifier below, each attempt narrows the strategy:

- Attempt 1 — free strategy choice
- Attempt 2 — strategy must be different category (repair catalog enforces)
- Attempt 3 — STOP, escalate to user or larger tier

Bounded by `kukuvaia.repair.max-attempts: 3` (default).

#### 13-category `FailureCategory` enum with retry policy

Villani reference: `autonomy.py:311-387`. Extends the 9-category `FindingCategory` from Pillar 5 (those are _what's wrong with the output_) with _why it went wrong_ categories for execution:

```java
public enum FailureCategory {
    MODEL_CONFUSION          (Retry.DIFFERENT_PROMPT),
    TOOL_SCHEMA_MISMATCH     (Retry.FIX_ARGS),
    TOOL_RUNTIME_ERROR       (Retry.SAME),
    TEST_FAILURE             (Retry.REPAIR_CODE),
    COMPILATION_ERROR        (Retry.REPAIR_CODE),
    PERMISSION_DENIED        (Retry.NEVER),
    RATE_LIMITED             (Retry.BACKOFF),
    TIMEOUT                  (Retry.BACKOFF),
    NETWORK_ERROR            (Retry.BACKOFF),
    FILE_NOT_FOUND           (Retry.LOCATE_FIRST),
    MERGE_CONFLICT           (Retry.NEVER),        // human
    OUT_OF_SCOPE             (Retry.NEVER),        // user clarification
    UNKNOWN                  (Retry.ESCALATE);     // try once, then stop
}
```

Classifier is pure keyword matching over the tool result + exception type. No LLM call.

`RepairEngine` reads the category → decides retry policy. Decision is a table lookup, not a reasoning step.

#### Opportunity priority ranking (empirical-weighted)

Villani reference: `autonomy.py:423-433`, `autonomous.py:496-506`. When there are multiple possible next actions, rank by:

```
score = (static_priority * 0.6) + (empirical_win_rate[category] * 0.4)
```

`empirical_win_rate` is a per-category rolling success rate maintained by Pillar 3 (Calibration). Start at 0.5 for unknown categories; update on every `PASS` / `FAIL` outcome.

The supervisor always picks the top-scored opportunity — no LLM "which should I do next?" call for autonomous workflows. Manual chat is unaffected; this fires only inside autonomous / wave-based execution.

#### Scope expansion lock

Villani reference: `state_runtime.py:541-575`. Once an action is in progress, agent declares `intendedTargets: List<Path>` (the files it expects to change). Any edit outside that list requires a one-time `scope_expansion` with evidence (a prior Read of the adjacent path). After that single expansion, further widening = `BLOCKED`.

Implementation: boolean `scopeExpansionUsed` in `ExecutionContext`, enforced by `ToolCallAdvisor` before each `Patch`/`Write` call. Blocks the `SuspiciousBreadth` failure class at the tool boundary rather than after the fact.

#### Read-before-edit guard

Villani reference: `state_runtime.py:518-539`. Hard rule: cannot `Patch` or `Write` a file that the agent hasn't `Read` in the current turn. For files that exist, the guard auto-inserts a `Read` call before the edit (no LLM involvement). For files that don't exist, `Patch` is rejected (must use `Write` explicitly, with a declared intent).

Removes an entire class of hallucinated-edit bugs on small models.

---

## Determinism-first adoption path

The overarching design principle, reinforced by Villani's benchmark: **runtime discipline beats model size**. Every decision that can move from an LLM call to a deterministic rule improves cost, latency, and honesty simultaneously.

### ROI-ranked implementation order

When Pillar 5 activates, implement mechanisms in this order (most deterministic / highest ROI first):

| # | Mechanism | LLM cost saved | Risk | Dependencies |
|---|---|---|---|---|
| 1 | Stop decision via category-state enum-switch (P14 pattern) | ~30% | Low | `MissionState.categoryStates` |
| 2 | Category state tracking (P14 pattern) | ~25% | Low | None — pure state update |
| 3 | VerificationEngine with 2 findings (`IncompleteEdit`, `SuspiciousBreadth`) | ~20% | Medium | Before/after snapshots |
| 4 | `FailureCategory` enum + keyword classifier | ~18% | Low | None |
| 5 | Atomic message units + signal-token compaction (P14) | ~15% | Medium | Message window wrapper |
| 6 | Repair strategy catalog (table lookup, not LLM) | ~10% | Low | `FailureCategory` |

### Anti-patterns — where not to let the LLM decide

Even Villani leaves a few decisions to the LLM unnecessarily. Kukuvaia should make these deterministic from day one:

| Decision | Villani current | Deterministic replacement |
|---|---|---|
| Repair strategy choice | Free-form repair prompt | Enum lookup: `FailureCategory → Retry policy → strategy catalog entry` |
| RED_TEAM finding severity | Adversarial LLM labels `AUTO_FIX`/`RECOMMEND`/`INFO` | Enum-switch: contradicts `knownFacts`/`excludedOptions` → `AUTO_FIX`; optional optimisation → `RECOMMEND`; everything else → `INFO` |
| `delegateToWorker` choice (P14) | Supervisor LLM decides per call | Rule: `if task_contract == INSPECTION && pressure >= MODERATE && context_messages < 10 → delegate` |
| Patch vs Write choice | LLM tool selection | Heuristic: file exists + size < 10KB + line count < 200 → `Patch`; else → `Write` |
| Scope expansion permission | Not in scope (already deterministic in Villani) | Keep as-is — boolean flag with evidence check |

Each anti-pattern replacement is additive: LLM can still propose, but the rule gates the action. If the rule says `no`, the LLM is asked to pick differently, not to argue.

### Why this path matters for self-hosted kukuvaia

Open-source kukuvaia targets operators running their own models on their own hardware — often 7B-27B class. Every decision left to the LLM is a decision that fails more often on a small model. The 6+5 mechanisms above move the failure surface from "LLM reasoning quality" to "our rule coverage" — a much more fixable problem.

Villani's 92.5% success rate at 27B vs Claude Code's 70% is the empirical case for this path. We don't need to be smarter; we need to constrain the decision space harder.

### Phased activation (Pillar 5)

1. **Phase A** — `VerificationEngine` service + `Finding` model + `SuspiciousBreadth` and `IncompleteEdit` findings (the two highest-value categories, both mechanical).
2. **Phase B** — Add `Regression` (run test command from persona config), `BrokenReference` (parse-only check for Java/Go patches).
3. **Phase C** — Fingerprint + loop detection, auto-escalation hook into P19.
4. **Phase D** — `block-on-fail=true` default for at least one persona (start with a "strict" persona for infra-mutating operations).

---

## Two verification moments — PRE-HOC and POST-HOC

Fact checking happens at two distinct moments. They are not substitutes; they compose.

| Moment | What it does | When it fires |
|--------|--------------|---------------|
| **PRE-HOC grounding** | Check facts **before** asserting. Supervisor detects a grounded-domain claim and calls the right tool (expert / web search / DB) before returning text. | Every LLM output where it matters — normal chat AND planning DRAFTING. |
| **POST-HOC review** | After the output is drafted, a separate adversarial pass reads it and surfaces likely errors. | Structured outputs where the cost of re-reading is justified — planning only. |

PRE-HOC stops bad claims from being produced. POST-HOC catches what slipped through. In a plan both fire. In a quick chat reply only PRE-HOC fires, because running a red-team pass on every "what time is it in Tokyo?" is absurd.

## Applicability of each pillar

| Pillar | Normal chat | `/plan` | Rationale |
|--------|-------------|---------|-----------|
| **1. Provenance Ledger** | ✅ | ✅ | Foundational. Every `OutputBlock` carries sources, everywhere. |
| **2. Red-Team Verification** | ❌ | ✅ | POST-HOC pass on plan _text_ doubles cost and latency. Only worth it for structured, consequential outputs (plans). |
| **3. Empirical Calibration** | ✅ | ✅ | Chat is the majority of traffic and the main data source for calibration curves. |
| **4. Expert Marketplace** | ✅ | ✅ | Domain routing benefits every turn, not only planning. Geography-expert helps `"ile godzin z Warszawy do Zakopanego?"` the same way it helps a `/plan`. |
| **5. VerificationEngine** | ✅ (side-effecting turns only) | ✅ (during execution) | Fires whenever the agent runs a side-effecting tool — patch, bash, DB write. Read-only turns skip it (no cost, nothing to verify). |

## Flow — normal chat (PRE-HOC only)

User: _"jak dojadę samochodem z Warszawy na Kretę?"_

1. Supervisor receives the turn.
2. Domain classifier tags it as `geography` / `travel`.
3. Pillar 3 (calibration) reports: the active model has historically low calibration in `geography` → **force grounding**.
4. Pillar 4 (expert) routes the question to `geography-expert`, which calls `openstreetmap_query` and `distance_calc`.
5. Expert returns structured facts: Crete is an island, no land route, ferry required from Piraeus.
6. Supervisor composes the reply; Pillar 1 tags each factual block with `Provenance(WEB, osm:..., VERIFIED)`.
7. User sees the answer colour-coded green on verified facts.

No red-team pass. No planning phases.

## Flow — `/plan` (PRE-HOC + POST-HOC)

`/plan trip to Crete, we go by our own car`

1. **DISCOVERY** — facts extracted (Pillar 1 & 4 already active). Ambiguities surfaced.
2. **DRAFTING** — plan drafted with PRE-HOC grounding for every factual claim (same mechanism as chat above).
3. **RED_TEAM** — Pillar 2 fires. A separate adversarial pass re-reads the plan, flags weaknesses: _"Crete is an island — a direct land route from Greece is not possible. Verify the ferry leg is explicit."_
4. **APPROVAL** — user sees plan + red-team findings + provenance colour-coding.
5. **Calibration** — outcome logged (Pillar 3) for future routing decisions.

At no point does the system pretend to know more than it does. That is the product.

---

## Phased activation

Each pillar ships independently once a trigger is met. Order below is the recommended MVP sequence — small first pillars unlock the later ones.

### Phase 1 — Provenance plumbing (Pillar 1, narrow)

- Extend `OutputBlock` with `sources: List<Provenance>` field.
- Wire tool-call results to emit provenance automatically.
- Ship CLI rendering (colour indicator).
- **Trigger:** ready now — foundational, small scope.

### Phase 2 — Red-team verification (Pillar 2)

- Add `RED_TEAM` phase to `PlanningModeService` (between DRAFTING and APPROVAL).
- `Finding` record with `severity ∈ {AUTO_FIX, RECOMMEND, INFO}` + strict enum-switch dispatcher (no reflection, no `eval`).
- `AUTO_FIX` path: constrained re-DRAFTING pass, 1 iteration max, budget cap `kukuvaia.planning.red-team.max-auto-fixes` (default 3), rejected if contradicts `knownFacts` / `excludedOptions`.
- `RECOMMEND` path: attached to `ReviewBlock`, surfaced in APPROVAL panel with per-item checkboxes; user selection triggers one more revision pass.
- `INFO` path: audit trail only, admin-visible, not shown in CLI.
- New `ReviewBlock` output type with fields `{autoFixes, recommendations, info}` — each a `List<Finding>`.
- `plan_revisions` audit table — captures `(plan_id, trigger=AUTO_FIX|RECOMMEND, finding_id, before_steps_json, after_steps_json, applied_at)`.
- `/plan --no-auto-fix` flag for users who want full visibility (all findings surface as RECOMMEND).
- Gate behind persona flag so it can be enabled selectively; default model = supervisor-tier with adversarial prompt, escalate to advisor-tier only for `persona.reviewTier=advisor` or `--strict`.
- **Trigger:** when users report factual errors in plans (which just happened in practice — the Crete case).

### Phase 3 — Calibration capture (Pillar 3)

- Add `calibration_events` table and write path.
- Domain classifier (simple keyword rule, upgrade later).
- Correction detection via user feedback + subsequent tool-call contradiction.
- **Trigger:** after Phase 1, once provenance data is flowing.

### Phase 4 — Expert loader (Pillar 4)

- Load `.kukuvaia/experts/*.yaml` at startup.
- `DelegationTools.consultExpert` tool registered.
- Ship 2–3 built-in experts (geography, legal-pl, finance) as reference.
- **Trigger:** when there is at least one external user asking "how do I add my own expert?".

### Phase 5 — Calibration-driven grounding (feedback loop)

- Wire calibration rollups into advisor: force-ground claims in low-calibration domains.
- **Trigger:** once `calibration_events` has N ≥ 500 per domain for the dominant model.

### Phase 6 — VerificationEngine (Pillar 5)

- Implement `VerificationEngine` service + `Finding` / `FindingCategory` sealed hierarchy.
- Mark mutating tools with `@Tool(hasSideEffect=true)` or equivalent metadata; advisor watches for those.
- Start with 2 finding categories (`IncompleteEdit`, `SuspiciousBreadth`) — highest-value mechanical checks, no model calls needed.
- Default `block-on-fail=false` (observational mode) — surface verification as a new `ReviewBlock` alongside the answer.
- Add `kukuvaia.verification.*` config with sensible defaults.
- **Trigger:** when the first operator reports "the agent claimed it edited X but it didn't" — which is the exact failure mode Villani's data shows smaller models fall into most.

### Phase 7 — Determinism extensions (Villani R2 patterns)

Activated alongside or immediately after Phase 6, in the ROI order from the "Determinism-first adoption path" section above:

- **7a** — Category state tracking in `MissionState` + stop-decision enum-switch.
- **7b** — Before/after snapshots in `ExecutionContext` (prerequisite for the remaining Pillar 5 finding categories).
- **7c** — `FailureCategory` enum (13 categories) + keyword classifier + retry policy table.
- **7d** — Atomic message units + signal-token compaction (P14 integration point).
- **7e** — `LineageTask` state machine with terminal fingerprint.
- **7f** — Repair engine with prior-attempts context + max-attempts cap.
- **7g** — Scope expansion lock + read-before-edit guard enforced at `ToolCallAdvisor`.
- **7h** — Opportunity priority ranking (autonomous workflows only).
- **7i** — Eliminate the four anti-patterns — move repair strategy, RED_TEAM severity, `delegateToWorker` choice, and Patch/Write choice to deterministic rules.

Each sub-phase is independent and can ship behind its own feature flag. Trigger: same as Phase 6 — first failure-mode report from a small-model deployment.

---

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Provenance capture discipline slips over time | Lint: any new tool must return provenance; CI check on `@Tool` methods. |
| Red-team pass doubles cost | Gate per persona / per plan size; default off, opt-in. |
| Calibration data is noisy with small N | Hard-rule domain grounding remains primary for first months; calibration is augmentation, not replacement. |
| Expert marketplace fragmentation | Curate a small set of first-party experts; community experts must include provenance tools. |
| Messaging risk ("more honest" sets high expectations) | Lead with facts: "model-only" is labelled, user learns the colour. Under-promise by design. |

---

## Rollback

All four pillars are strictly additive:

- Remove `sources` field from `OutputBlock` (clients ignore unknown fields gracefully).
- Disable `RED_TEAM` phase via config flag.
- `calibration_events` table can be dropped without side effect on chat.
- `.kukuvaia/experts/` directory is opt-in; removing it reverts to plain sub-agents.

No data migrations required to unwind.

---

## Relationship to other plans

- `docs/work/005-conversation-summarization/plan.md` — orthogonal (compacts old turns); composes cleanly.
- `docs/work/015-tiered-context/plan.md` — orthogonal (context window sizing); expert marketplace (Pillar 4) naturally uses tiered context budgets once P14 is active. `ContextPressureAdvisor` (P14) consumes Pillar 5 loop-detection signals to decide when to escalate or prune.
- `/tmp/villani-code` research (mmprotest/villani-code, 2026-04-20) — source of Pillar 5 design. Their benchmark shows that an adversarial verifier lets a 27B open model outperform Claude Code on bounded repo tasks.
- `docs/work/007-content-moderation/plan.md` — Red-team phase may share infrastructure with moderation passes.
- `docs/work/001-observability/plan.md` — calibration dashboard piggybacks on observability surface.

## Open questions (resolve at activation time, not now)

- Provenance: store in-message (output blocks) only, or also persist to a dedicated `facts` table for cross-session queries?
- Red-team: same model as supervisor or always a different tier?
- Expert marketplace: signed YAMLs to prevent tampering in public repo?
- Calibration: how to weight self-reported confidence vs. empirical — or ignore self-reported entirely?

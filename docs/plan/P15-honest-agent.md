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
| **2. Red-Team Verification** | ❌ | ✅ | POST-HOC pass doubles cost and latency. Only worth it for structured, consequential outputs (plans). |
| **3. Empirical Calibration** | ✅ | ✅ | Chat is the majority of traffic and the main data source for calibration curves. |
| **4. Expert Marketplace** | ✅ | ✅ | Domain routing benefits every turn, not only planning. Geography-expert helps `"ile godzin z Warszawy do Zakopanego?"` the same way it helps a `/plan`. |

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

- `docs/plan/P04-conversation-summarization.md` — orthogonal (compacts old turns); composes cleanly.
- `docs/plan/P14-tiered-context.md` — orthogonal (context window sizing); expert marketplace (Pillar 4) naturally uses tiered context budgets once P14 is active.
- `docs/plan/P06-content-moderation.md` — Red-team phase may share infrastructure with moderation passes.
- `docs/plan/P01-observability.md` — calibration dashboard piggybacks on observability surface.

## Open questions (resolve at activation time, not now)

- Provenance: store in-message (output blocks) only, or also persist to a dedicated `facts` table for cross-session queries?
- Red-team: same model as supervisor or always a different tier?
- Expert marketplace: signed YAMLs to prevent tampering in public repo?
- Calibration: how to weight self-reported confidence vs. empirical — or ignore self-reported entirely?

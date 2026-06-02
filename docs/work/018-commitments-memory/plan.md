# P17 — Commitments & Pending Tasks Memory

**Status:** Draft — document only.
**Created:** 2026-04-19
**Relationship to other plans:** Extends the existing `kukuvaia-memory` subsystem (P04/Phase 4D). Complements `PlanningModeService` (plans) by covering the gap between _structured multi-step plans_ and _tiny user commitments_. Integrates with `SessionContextAdvisor` (surfaces commitments proactively). Orthogonal to P15 (honest agent) and P16 (stepped reasoning).

## Motivation

Today kukuvaia tracks "things to finish" only through `PlanningModeService` — and a plan must be explicitly invoked with `/plan`. Anything a user mentions in passing ("remind me to rebook the Crete flight next week", "I still need to respond to that Slack thread") evaporates after the turn. When the user later asks _"do I have anything unfinished?"_ the agent only looks at plans, not at dropped commitments.

The current memory subsystem (`SmartMemoryRepository`, categories: `user` / `project` / `feedback` / `reference`) is a **facts store**, not a **task store**. Memory entries have no lifecycle (`pending` / `done`), no due date, and no concept of "still open". Adding commitment tracking by overloading memory categories would mix semantics and mask bugs.

This plan introduces a dedicated, lightweight commitments table plus the minimum glue to surface it proactively.

## Non-goals

- **Full todo manager** — we are not building a Things/Todoist clone. No projects, tags, nested lists, repeat schedules.
- **Cross-user shared lists** — commitments are per-user, per-session scope; no collaboration features.
- **Push notifications / emails** — surfacing is via the existing `SessionContextAdvisor` and CLI proactivity. Scheduled reminders (daemon) are a stretch goal.
- **Replace `PlanningModeService`** — plans remain the structured multi-step construct. Commitments are atomic.

## Data model

### New table — `kukuvaia.commitments`

```sql
CREATE TABLE kukuvaia.commitments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    session_id      VARCHAR(255),       -- optional: where it was first mentioned
    summary         TEXT NOT NULL,      -- one-line "what needs to happen"
    detail          TEXT,               -- optional longer context
    status          VARCHAR(20) NOT NULL DEFAULT 'open',
                    -- open | in_progress | done | dropped
    source          VARCHAR(20) NOT NULL DEFAULT 'extracted',
                    -- extracted (LLM noticed) | explicit (user typed /todo) | tool
    due_hint        TEXT,               -- free-text hint: "next week", "before Friday"
    due_at          TIMESTAMPTZ,        -- optional parsed due date (best-effort)
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    relevance_score DOUBLE PRECISION DEFAULT 1.0  -- decays over time if not referenced
);

CREATE INDEX idx_commitments_user_open ON kukuvaia.commitments(user_id, status)
    WHERE status IN ('open', 'in_progress');
CREATE INDEX idx_commitments_session ON kukuvaia.commitments(session_id, status);
```

Flyway migration `V13__create_commitments.sql`.

### Java model

```java
public record Commitment(
    UUID id,
    String userId,
    String sessionId,
    String summary,
    String detail,
    Status status,              // OPEN, IN_PROGRESS, DONE, DROPPED
    Source source,              // EXTRACTED, EXPLICIT, TOOL
    String dueHint,
    Instant dueAt,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    double relevanceScore
) {}
```

## Components

### 1. `CommitmentRepository` (new, in `kukuvaia-memory`)

Thin JDBC repository mirroring the pattern of `SmartMemoryRepository`. Methods:

- `save(Commitment)` — insert or upsert on id
- `findOpenByUser(userId, limit)` — for proactive surfacing
- `findByUserAndSession(userId, sessionId)` — session-scoped
- `markStatus(id, status)` — lifecycle transitions
- `touchAccess(id)` — bumps `relevance_score` back to 1.0
- `decayAll(factor, olderThan)` — nightly consolidation

### 2. `@Tool` surface — `CommitmentTools` (new, in `kukuvaia-core`)

```java
@Tool(description = "Record a new commitment the user has mentioned...")
Map<String,Object> addCommitment(
    @ToolParam String summary,
    @ToolParam(required = false) String detail,
    @ToolParam(required = false) String dueHint);

@Tool(description = "Mark a commitment as done, in_progress, or dropped.")
Map<String,Object> updateCommitmentStatus(
    @ToolParam String commitmentId,
    @ToolParam String status);

@Tool(description = "List the user's open commitments.")
List<Map<String,Object>> listCommitments(
    @ToolParam(required = false) Integer limit);
```

LLM can call these during chat. Session and user are resolved via `PlanningTools.getCurrentSessionId()` (same ThreadLocal pattern).

### 3. Slash commands — explicit user control

Deterministic (no LLM):

- `/todo add <text>` — user records commitment directly
- `/todo list` — renders open commitments as a `TableBlock`
- `/todo done <id>` — marks complete
- `/todo drop <id>` — marks dropped

Handled in `CommandRegistry` as new commands. No LLM round-trip.

### 4. Extraction hook — `CommitmentExtractionService`

Piggybacks on existing `MemoryExtractionService` which already runs async after each chat turn. Extend its extraction prompt to also return:

```json
{ "commitments": [
    { "summary": "...", "detail": "...", "dueHint": "..." }
]}
```

LLM decides; deterministic fallback: scan user message for trigger phrases (`"przypomnij mi"`, `"muszę"`, `"remind me"`, `"I need to"`, `"I should"`) — if found and no commitment extracted, create one with `source='extracted'`.

Keep fallback simple — no stemming, just keyword contains. Over-extraction is better than under-extraction since users can `/todo drop` false positives.

### 5. `SessionContextAdvisor` — surface open commitments

Extend the DB-derived context block:

```
## Session Context
- This is an ONGOING session (24 prior messages).
- Plans attached to this session:
  - Podróż Łódź → Praga samochodem (draft)
- Open commitments for this user:
  - Rebook Crete flight  (due hint: next week)
  - Respond to Slack thread about ETSL
```

Cap: 5 most recent open commitments. If the user explicitly asks about "things to finish", LLM now has both plans AND commitments grounded in DB.

### 6. Decay + consolidation — reuse memory-consolidation cron

The existing `MemoryConsolidationService` already runs at `MEMORY_CONSOLIDATION_CRON` (default 03:00). Add a pass:

- Decay `relevance_score` by `decay-factor` (default 0.95) for commitments not touched in `unaccessed-days`.
- Archive (status `dropped`) commitments with `relevance_score < 0.1` AND `status = 'open'` for > 30 days.
- Stale `in_progress` with no update in 14 days → log warning + nudge on next session.

## Flow examples

### Extract-on-mention

```
User: "Btw I still need to reply to Marek's email."
  → chat turn completes normally
  → MemoryExtractionService (async) sees the message, LLM extracts:
      [{ summary: "Reply to Marek's email", source: "extracted" }]
  → CommitmentRepository.save(...)
```

Next time the user opens a session, `SessionContextAdvisor` surfaces: _"You have an open commitment: Reply to Marek's email — still relevant?"_

### Explicit user add

```
User: /todo add sprawdzić czy kreta ma dostępny ferry w lipcu
  → CommandRegistry handles, inserts directly
  → TextBlock: "✓ Commitment saved: sprawdzić czy kreta ma dostępny ferry w lipcu"
```

### Proactive reminder

```
User (new session): "hej"
  → SessionContextAdvisor injects 3 open commitments
  → LLM: "Witaj! Masz 3 otwarte rzeczy do dokończenia:
          1. Reply to Marek's email
          2. Check Crete ferry availability in July
          3. Rebook flight
          Którymi chcesz się zająć, a które odłożyć/skasować?"
```

## Acceptance criteria

- [ ] Flyway V13 migration creates the `commitments` table; tests pass against a Testcontainers Postgres.
- [ ] `CommitmentRepository` CRUD covered by unit tests.
- [ ] `/todo add`, `/todo list`, `/todo done <id>`, `/todo drop <id>` work end-to-end.
- [ ] `@Tool`-registered `addCommitment` / `updateCommitmentStatus` / `listCommitments` callable from the LLM.
- [ ] `CommitmentExtractionService` extracts at least one commitment from a seeded test conversation containing `"przypomnij mi żeby"`.
- [ ] `SessionContextAdvisor` includes up to 5 open commitments in its injected block when they exist, omits the section when none.
- [ ] Decay pass downgrades `relevance_score`; unit test freezes time and verifies the math.
- [ ] Integration test: user mentions a commitment → starts new session → agent proactively asks about it.

## Observability (P01 integration)

- Counter `kukuvaia.commitments.created{source}` on save.
- Counter `kukuvaia.commitments.status_changed{from,to}` on lifecycle transitions.
- Gauge `kukuvaia.commitments.open` (per-user cardinality risk — keep user label off Prometheus; expose via admin UI instead).
- `SpanEventBlock` at extraction time: `memory:commitment-extracted`.

## Effort estimate

| Piece | Effort |
|-------|--------|
| Flyway V13 + repository + tests | 1 day |
| `CommitmentTools` (@Tool surface) | 0.5 day |
| Slash commands | 0.5 day |
| Extraction integration + keyword fallback | 1 day |
| `SessionContextAdvisor` extension | 0.5 day |
| Decay pass in consolidation service | 0.5 day |
| End-to-end tests + demo | 1 day |
| **Total** | **~5 days** (≈ 1 week per the informal "M" effort class) |

## Risks

| Risk | Mitigation |
|------|------------|
| Over-extraction — LLM records every casual mention as a commitment | Conservative extraction prompt + `/todo drop` is one keyword; decay keeps noise out of long-term surface. |
| Commitment noise overwhelms prompt | Cap at 5 in `SessionContextAdvisor`; decay-score-based ordering; admin UI can show full list. |
| Status drift — user completes something but never tells the agent | Decay handles this naturally; explicit `/todo done` is a one-line CLI invocation. |
| Due date parsing brittle | `due_hint` remains free-text authoritative; `due_at` is best-effort; if parsing fails, leave null and render hint verbatim. |
| Cross-session privacy (user A sees user B's commitments) | Scoped by `user_id` in every query; tests include user-isolation assertion. |

## Rollback

- Revert Flyway: drop table (no data dependencies outside this feature).
- Remove `CommitmentTools` @Tool registration.
- Revert `SessionContextAdvisor` extension (single if-block).
- Slash commands: unregister from `CommandRegistry`.

All changes additive; no existing behaviour relies on commitments.

## Trigger for activation

Do **not** start implementation until one of the following appears:

- Users complain that the agent forgets things they casually mentioned (≥ 2 reports).
- A real use-case where `/plan` feels heavyweight for a one-liner "remind me to X" (observed in live sessions).
- A downstream feature (e.g. Obsidian integration P11, daily digest daemon) explicitly needs a commitments data source.

Until then: single `/plan` construct is sufficient for "things to finish".

## Future extensions

- **Scheduled reminders**: daemon (existing `DaemonAgentService`) wakes at `due_at`, emits notification via webhook/email.
- **Obsidian sync** (P11): export open commitments as a Markdown file in the user's vault.
- **Skill integration**: reflection loop (P12) can learn from completed commitments which kinds of tasks the user follows through on vs drops.
- **Cross-session merge** — detect duplicate commitments mentioned across sessions; merge into one.

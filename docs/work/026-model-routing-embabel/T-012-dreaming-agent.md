# T12: Dreaming Agent

**Status**: `pending`
**Tier**: 4 — Intelligence
**Depends On**: T03, T04, T05, T10
**Blocks**: T14
**Source**: `docs/analyzes/dreaming-agent-analysis.md`

## Goal

Scheduled autonomous agent that performs background self-inspection: health checks, memory consolidation, log analysis, cross-service inspection. Produces DreamReport with ranked recommendations (propose, never act).

## Scope

### Dream Tasks (pluggable modules)

| Task | Schedule | Model | LLM? |
|------|----------|-------|------|
| Health Check | Hourly | None | No — deterministic |
| Config Audit | On-change | None | No — deterministic |
| Memory Consolidation | Nightly | Haiku (worker) | Yes |
| Log Analysis | Hourly light, daily deep | Haiku + Sonnet | Yes |
| Model Scout | Daily | Haiku + Sonnet | Yes |
| Cross-Service Inspection | Daily | Sonnet | Yes |

### Dream Scheduler
- Cron-based via daemon system (DaemonScheduleGuard, DaemonBudgetGuard)
- Each task independently schedulable/disableable
- Estimated daily cost: ~30-45k tokens (within 100k daemon budget)

### DreamReport Schema
- `dream_reports` table — run metadata, health snapshot, summary
- `dream_recommendations` table — individual recommendations with priority, confidence, status (pending/accepted/rejected)

### API Endpoints
- `GET /api/dream/reports` — list reports
- `GET /api/dream/reports/latest` — most recent
- `POST /api/dream/trigger` — manual trigger
- `POST /api/dream/recommendations/{id}/accept` — accept recommendation
- `POST /api/dream/recommendations/{id}/reject` — reject with reason

### Core Principle: Propose, Never Act
Dream agent NEVER automatically changes models, deletes memories, or modifies configs. Produces recommendations for human approval.

## File Inventory

### Create
- Migration: `V9__create_dream_tables.sql`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/dream/DreamingAgent.kt`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/dream/DreamModels.kt`
- Dream task implementations (health, config audit, memory, log, model scout)
- `kukuvaia-core/src/main/java/ai/kukuvaia/api/DreamController.java`
- Repositories for dream_reports, dream_recommendations

## Acceptance Criteria

- [ ] Health check runs hourly, produces health report (no LLM)
- [ ] Config audit detects orphaned roles, missing env vars
- [ ] Memory consolidation identifies stale memories for decay
- [ ] DreamReport accessible via API with recommendations
- [ ] Accept/reject recommendations tracked in DB
- [ ] Manual trigger via `POST /api/dream/trigger` works
- [ ] Respects daemon token budget

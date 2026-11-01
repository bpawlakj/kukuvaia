---
id: T-009
title: "Generic webhooks → jobs (202 + task id); /ci and /alert as YAML; fix devops"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-008]
blocks: [T-016]
plan_anchor: d3--job-definitions-triggers-notifications
---

# T-009 — Generic webhooks → jobs (202 + task id); /ci and /alert as YAML; fix devops

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/api/webhook/WebhookController.java`
- built-in YAML jobs `ci-failure-analysis.yaml`, `alert-investigation.yaml`; `specialists/devops.yaml` or retarget to `analyst`

## Approach

- `POST /api/webhooks/{route}` resolves the job with `webhook: <route>` trigger; `PayloadSanitizer.extractFields(raw, job.input.fields)`; submit async; return **202** with task id.
- Existing filters unchanged (rate limit → auth → sanitize).
- `/alert` no longer targets a missing specialist.

## Acceptance

- Signed `/api/webhooks/ci` → 202 + task id; task later COMPLETED.
- Raw payload never appears in the stored prompt (only declared fields).
- Unknown route → 404; unauthenticated → 401 (existing tests green).

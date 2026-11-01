---
id: T-019
title: "Follow-up to T-009..T-011: close D3 security-review findings"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-009, T-010, T-011]
blocks: [T-016]
plan_anchor: d3--job-definitions-triggers-notifications
---

# T-019 — Follow-up to T-009..T-011: close D3 security-review findings

Follow-up to T-009..T-011. The D3 phase review (security-reviewer, commits af28c60, f6b3d79, c77690c)
returned PASS WITH FIXES.

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/security/WebhookAuthFilter.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/PgNotifyListener.java`, `PgNotifyDebouncer.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/def/PromptRenderer.java`, built-in `daemon/*.yaml`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/notify/WebhookNotificationSink.java`, `DaemonNotifier.java`

## Approach

1. (HIGH) Bearer-authenticated webhook bodies are read bounded (`MAX_BODY_BYTES`, 413) like HMAC ones.
2. Listener loop survives unchecked exceptions (logged, backoff), never dies silently.
3. Reconnect backoff resets only after the connection was stable for 60 s.
4. Dedicated LISTEN connection from the pool: accepted, documented (`maximum-pool-size` >= 2).
5. Debouncer: max-wait cap (no starvation by a steady trickle); buffer bounded at `max-batch-size`
   with overflow counted, flushed on the window — a storm of any size yields one run per window.
6. Field values are whitespace-collapsed when rendered; built-in prompts fence event data as untrusted.
7. PG NOTIFY `input.match` is evaluated per event; only matching events are aggregated.
8. Webhook sink without a secret stays inactive (fail closed); signature covers a timestamp
   (`X-Kukuvaia-Timestamp`, signed string `<ts>.<body>`) against replay.
9. Sinks run on a small bounded executor — a slow receiver never delays the run's release.
10/11 (LOW, accepted): sink URL is server config only; server-side logs may contain connection error text.

## Acceptance

- Bearer webhook with a body over the cap → 413, controller not reached.
- Listener keeps listening after a RuntimeException from the debouncer.
- 250 notifications in one window → one run with `batch_size` 250; a steady trickle is flushed by max-wait.
- Batch with `failed` and `error` events matches `match: { status: [failed, error] }`; non-matching events are not aggregated.
- Rendered prompt values contain no newlines.
- Webhook sink with URL but no secret sends nothing; signature verifies over `<ts>.<body>`.
- Full suite green.

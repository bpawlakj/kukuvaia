---
id: T-011
title: "NotificationSink SPI + Log + signed Webhook sinks"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: c77690c
depends_on: [T-008]
blocks: [T-016]
plan_anchor: d3--job-definitions-triggers-notifications
---

# T-011 — NotificationSink SPI + Log + signed Webhook sinks

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/notify/` (`NotificationSink`, `LogNotificationSink`, `WebhookNotificationSink`)
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/NotificationSanitizer.java` (used for every sink)

## Approach

- Runtime calls sinks listed in job `notify:` after completion; payload = `NotificationSanitizer.toExternalPayload` (summary only).
- Webhook sink: HMAC-SHA256 signature header, timeout, no retries storm (bounded), URL from config (not from job input).

## Acceptance

- Sink tests: payload has no full result, signature verifies, sink failure does not fail the task.

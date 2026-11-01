---
id: T-015
title: "A2A spike → go/no-go for A2aExecutor"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-005]
blocks: []
plan_anchor: d5--external-agents-mcp-now-a2a-spike
---

# T-015 — A2A spike → go/no-go for A2aExecutor

## Scope

- spike notes in this task's `## Notes`; if go: new `A2aExecutor` behind the SPI

## Approach

- Half day with `io.github.a2asdk:a2a-java-sdk-client` (1.0.0.Alpha3) against a sample A2A agent: send task, stream/poll result, auth.
- If green: implement `A2aExecutor`; otherwise make the loader reject `kind: a2a` with a clear message.

## Acceptance

- Written go/no-go decision with evidence; loader behaviour matches the decision.

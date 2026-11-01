---
id: T-014
title: "McpToolExecutor (kind: mcp)"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-005, T-013]
blocks: [T-016]
plan_anchor: d5--external-agents-mcp-now-a2a-spike
---

# T-014 — McpToolExecutor (kind: mcp)

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/exec/McpToolExecutor.java`

## Approach

- `kind: mcp`, `target: {connection, tool}`; arguments from a template over sanitized fields only; result through tool-result sanitization; per-job timeout; zero tokens (remote cost logged as out of scope).

## Acceptance

- YAML job against a stub MCP server → COMPLETED with tool output; timeout → TIMEOUT; unknown connection/tool → FAILED with clear error.

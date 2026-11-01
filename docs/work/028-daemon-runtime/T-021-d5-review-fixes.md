---
id: T-021
title: "Follow-up to T-013, T-014: close D5 security-review findings"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-013, T-014]
blocks: [T-016]
plan_anchor: d5--external-agents-mcp-now-a2a-spike
---

# T-021 — Follow-up to T-013, T-014: close D5 security-review findings

Follow-up to T-013 and T-014. The D5 phase review (security-reviewer, commits 7756c8f, 4886f99, 23d279d)
returned PASS WITH FIXES. (Its point that a timed-out `kind: mcp` run is charged tokens does not hold:
`conservativeCharge` is 0 for kinds that consume no local tokens.)

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/mcp/McpConnectionsCache.java`, `config/McpClientConfig.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/exec/McpToolExecutor.java`, `DaemonConfig.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/api/McpConnectionsController.java`

## Approach

1. (HIGH) Transports get a per-connection header customizer: only that connection's headers, only for
   requests to that connection's origin (scheme, host, port). The shared URI lookup matches at a URL
   boundary (no `https://api.x.com.evil.net`), and duplicate URLs are ambiguous → no headers.
2. (MEDIUM) see 1.
3. (HIGH) `kind: mcp` is deny-by-default: `kukuvaia.daemon.mcp-allowed-tools` (`connection/tool`) is
   checked at load and per run.
4. (MEDIUM) Admin API: `url` must be http(s) and not link-local (cloud metadata); credential-like header
   names (authorization, token, secret, key, cookie, password) must use `env:` references.
   Residual (documented): `/api/admin` shares the single API key; no admin role yet.
5. (MEDIUM) Admin API responses mask header values that are not `env:` references.
6. (MEDIUM, accepted) an in-flight MCP call is bounded by `spring.ai.mcp.client.request-timeout`; the
   runtime holds the slot until it ends (zombie hold). Documented.
7. (MEDIUM) Responses capped at the transport (4 MiB); structured output above 64 KiB is dropped;
   text is pre-capped before redaction.
8. (LOW) Name lists in error texts truncated; MCP init failures logged without the raw message.
9. (LOW) `sseEndpoint` must start with `/`.

## Acceptance

- Lookalike host / other port / path-prefix-without-boundary do not receive a connection's headers.
- A per-connection customizer never sends headers to another origin.
- `kind: mcp` job for a tool not in `mcp-allowed-tools` is rejected at load.
- Admin API rejects link-local URLs and literal credential headers; responses mask literal values.
- Oversized structured output is not stored.
- Full suite green.

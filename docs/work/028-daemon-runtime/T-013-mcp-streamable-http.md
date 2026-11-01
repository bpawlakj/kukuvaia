---
id: T-013
title: "MCP client streamable HTTP from DB + SSE auth-header fix"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: 7756c8f
depends_on: []
blocks: [T-014]
plan_anchor: d5--external-agents-mcp-now-a2a-spike
---

# T-013 — MCP client streamable HTTP from DB + SSE auth-header fix

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/config/McpClientConfig.java`, `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/mcp/*`
- `kukuvaia-engine/kukuvaia-app/src/main/resources/db/migration/V25__mcp_connections_transport.sql`

## Approach

- V25: `mcp_connections.transport` (`sse` | `streamable-http`, default `sse`) and `endpoint` (default `/mcp`).
- Build `NamedClientMcpTransport`s for streamable rows with `HttpClientStreamableHttpTransport` + existing `PerConnectionHeaderCustomizer`; add `McpClientCustomizer<HttpClientSseClientTransport.Builder>` so SSE connections send auth headers again; fix stale Javadoc.

## Acceptance

- Stub MCP server (JDK `HttpServer`, JSON-RPC + `mcp-session-id`) receives `Authorization` over streamable HTTP; session re-init after 404.
- SSE header regression test.
- Local `sl-aipdlc serve --http` lists tools via `McpToolDiscoveryLogger`.

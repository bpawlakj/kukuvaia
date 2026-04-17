# ETSL Integration

How kukuvaia-engine accesses ETSL content data.

## Model

ETSL content (outlines, sections, content items) lives in MongoDB. kukuvaia-engine does **not** connect to that MongoDB directly — it goes through a dedicated external **ETSL MCP server**, with kukuvaia-engine acting as an MCP client.

```
kukuvaia-engine ── MCP Client ──▶ ETSL MCP server ──▶ MongoDB (ETSL)
```

## Why

Separation of concerns. ETSL data access is its own service, versioned and deployed independently of the agent engine. This keeps the engine domain-agnostic and avoids coupling the agent stack to a specific content schema.

## What this means in practice

- No `MongoTools.java` in the engine — removed.
- No MongoDB driver dependency in `kukuvaia-core`.
- The engine uses the **Spring AI MCP Client starter** to connect to the ETSL MCP server.
- Tools such as `get_outline_raw`, `get_sections`, `get_content_items` are surfaced to the LLM as remote MCP tools, not local `@McpTool` methods.
- `PostgresTools` stays — PostgreSQL access to the `kukuvaia_data` schema (classifications, embeddings, etc.) remains direct because it is core engine data, not ETSL content.

## Configuration

The ETSL MCP server URL and auth are configured via Spring AI's MCP client properties in `application.yaml`. Connections are established at startup; tools are discovered dynamically and merged into the single tool callback list that the main `ChatClient` presents to the LLM.

## Implications for new work

- Adding a new ETSL-related tool: implement it in the ETSL MCP server, not in kukuvaia-engine.
- Adding a new non-ETSL data source: decide whether it is a **core engine dataset** (then `@McpTool` over JDBC/HTTP in the engine) or a **separately versioned domain** (then a dedicated MCP server and MCP client integration).

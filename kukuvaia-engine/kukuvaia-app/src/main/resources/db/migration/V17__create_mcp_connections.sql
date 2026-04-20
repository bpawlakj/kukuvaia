-- P22 Phase B — MCP client connections managed through the admin dashboard.
-- Replaces the earlier YAML-based `spring.ai.mcp.client.sse.connections.*`
-- configuration so ops can add/rotate remote MCP servers without a redeploy.
--
-- `headers` is a JSON object keyed by header name. Values are either:
--   * `env:VAR_NAME`     — resolved from process env at request time
--   * any other string    — used verbatim as the header value (literal)
--
-- Literal values are convenient for local dev; `env:` references keep
-- secrets out of the DB row per the kukuvaia credentials standard.

CREATE TABLE kukuvaia.mcp_connections (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL UNIQUE,
    url             TEXT NOT NULL,
    sse_endpoint    VARCHAR(100) DEFAULT '/sse',
    headers         JSONB DEFAULT '{}'::jsonb,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    description     TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_mcp_connections_enabled ON kukuvaia.mcp_connections(enabled);

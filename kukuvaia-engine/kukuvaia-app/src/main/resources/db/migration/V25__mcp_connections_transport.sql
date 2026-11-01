-- Daemon runtime D5 — MCP connections can use the Streamable HTTP transport (the MCP spec's replacement
-- for HTTP+SSE). Existing rows keep `sse`; `endpoint` is the Streamable HTTP path (default `/mcp`),
-- `sse_endpoint` stays the SSE path.

ALTER TABLE kukuvaia.mcp_connections
    ADD COLUMN transport VARCHAR(20) NOT NULL DEFAULT 'sse',
    ADD COLUMN endpoint  VARCHAR(100) NOT NULL DEFAULT '/mcp';

ALTER TABLE kukuvaia.mcp_connections
    ADD CONSTRAINT mcp_connections_transport_check CHECK (transport IN ('sse', 'streamable-http')),
    ADD CONSTRAINT mcp_connections_endpoint_check CHECK (endpoint LIKE '/%');

-- Register the validation-engine MCP server as a connection that the
-- kukuvaia-engine validator + rule-editor personas can call into.
--
-- Run this once against the kukuvaia Postgres database after you have a
-- validation-engine deployment to point at:
--
--   psql -U kukuvaia -d kukuvaia -f scripts/register-validation-engine-mcp.sql
--
-- The Authorization header value uses the `env:NAME` convention — the engine's
-- PerConnectionHeaderCustomizer resolves it from the process env at request time
-- so the token never lives in the database row. Set VALIDATION_ENGINE_TOKEN in
-- the engine's .env (or the deployment env) before restarting.
--
-- After insert: restart kukuvaia-app (or call POST /api/admin/mcp/refresh) so
-- Spring AI builds the McpSyncClient bean for the new connection. The
-- McpToolDiscoveryLogger should then print the validation-engine tool count —
-- expect 19 (per architecture §7 of sl-validation-engine).

INSERT INTO kukuvaia.mcp_connections (name, url, sse_endpoint, headers, enabled, description)
VALUES (
    'validation-engine',
    -- Adjust to match your validation-engine deployment URL
    COALESCE(current_setting('app.validation_engine_url', true), 'http://localhost:8081/mcp'),
    '/sse',
    '{"Authorization": "env:VALIDATION_ENGINE_TOKEN"}'::jsonb,
    true,
    'sl-validation-engine MCP server — drives the validator + rule-editor personas (TG2.C)'
)
ON CONFLICT (name) DO UPDATE
SET url         = EXCLUDED.url,
    headers     = EXCLUDED.headers,
    enabled     = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_at  = NOW();

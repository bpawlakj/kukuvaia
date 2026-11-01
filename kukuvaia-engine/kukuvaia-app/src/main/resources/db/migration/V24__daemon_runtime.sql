-- Daemon runtime: daemon_tasks becomes the audit log for every kind of daemon work
-- (LLM specialists, Java jobs, Embabel agents, MCP/A2A agents), with an async lifecycle
-- (QUEUED -> RUNNING -> COMPLETED | FAILED | TIMEOUT | SKIPPED | CANCELLED) and split token usage.
-- Existing rows (V23: specialist-only, synchronous) stay valid: kind defaults to 'specialist'
-- and target is backfilled from specialist_type.

ALTER TABLE kukuvaia_agent.daemon_tasks
    ALTER COLUMN specialist_type DROP NOT NULL,
    ALTER COLUMN prompt DROP NOT NULL,
    ALTER COLUMN started_at DROP NOT NULL,
    ALTER COLUMN started_at DROP DEFAULT,
    ADD COLUMN kind              VARCHAR(20) NOT NULL DEFAULT 'specialist'
        CHECK (kind IN ('specialist', 'java', 'embabel', 'mcp', 'a2a')),
    ADD COLUMN target            TEXT,
    ADD COLUMN provider          TEXT,
    ADD COLUMN model             TEXT,
    ADD COLUMN prompt_tokens     INTEGER NOT NULL DEFAULT 0 CHECK (prompt_tokens >= 0),
    ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0 CHECK (completion_tokens >= 0),
    ADD COLUMN error             TEXT,
    ADD COLUMN output            JSONB,
    ADD COLUMN parent_task_id    BIGINT REFERENCES kukuvaia_agent.daemon_tasks (id) ON DELETE SET NULL,
    ADD COLUMN queued_at         TIMESTAMPTZ;

UPDATE kukuvaia_agent.daemon_tasks SET target = specialist_type WHERE target IS NULL;

-- V23 failures stored the error text in `result`; move it to the new `error` column.
UPDATE kukuvaia_agent.daemon_tasks SET error = result, result = NULL WHERE status = 'FAILED' AND error IS NULL;

ALTER TABLE kukuvaia_agent.daemon_tasks DROP CONSTRAINT IF EXISTS daemon_tasks_status_check;
ALTER TABLE kukuvaia_agent.daemon_tasks ADD CONSTRAINT daemon_tasks_status_check
    CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'TIMEOUT', 'SKIPPED', 'CANCELLED'));

CREATE INDEX idx_daemon_tasks_name_created ON kukuvaia_agent.daemon_tasks (name, created_at);
CREATE INDEX idx_daemon_tasks_status ON kukuvaia_agent.daemon_tasks (status);

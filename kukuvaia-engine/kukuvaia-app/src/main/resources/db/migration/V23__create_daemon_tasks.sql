-- Daemon execution state belongs to the agent schema, independent of pipeline data.
CREATE SCHEMA IF NOT EXISTS kukuvaia_agent;

CREATE TABLE kukuvaia_agent.daemon_tasks (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL,
    specialist_type TEXT NOT NULL,
    prompt          TEXT NOT NULL,
    trigger_source  TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    result          TEXT,
    token_usage     INTEGER NOT NULL DEFAULT 0 CHECK (token_usage >= 0),
    duration_ms     BIGINT CHECK (duration_ms >= 0),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_daemon_tasks_created_at ON kukuvaia_agent.daemon_tasks (created_at);

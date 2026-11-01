-- Queued jobs count against the day they actually start, including after midnight.
CREATE INDEX idx_daemon_tasks_execution_day
    ON kukuvaia_agent.daemon_tasks ((COALESCE(started_at, created_at)));

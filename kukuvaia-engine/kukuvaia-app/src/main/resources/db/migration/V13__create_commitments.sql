-- P17 — Commitments & Pending Tasks Memory
-- Lightweight task store for casual user mentions that don't warrant a full /plan.

CREATE TABLE kukuvaia.commitments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    session_id      VARCHAR(255),
    summary         TEXT NOT NULL,
    detail          TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open', 'in_progress', 'done', 'dropped')),
    source          VARCHAR(20) NOT NULL DEFAULT 'extracted'
                    CHECK (source IN ('extracted', 'explicit', 'tool')),
    due_hint        TEXT,
    due_at          TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    relevance_score DOUBLE PRECISION NOT NULL DEFAULT 1.0
);

CREATE INDEX idx_commitments_user_open ON kukuvaia.commitments(user_id, status)
    WHERE status IN ('open', 'in_progress');

CREATE INDEX idx_commitments_session ON kukuvaia.commitments(session_id, status);

CREATE INDEX idx_commitments_user_updated ON kukuvaia.commitments(user_id, updated_at DESC);

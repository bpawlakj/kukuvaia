-- Dreaming agent: autonomous self-inspection reports and recommendations.

CREATE TABLE kukuvaia.dream_reports (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP,
    status          VARCHAR(20) DEFAULT 'running'
                    CHECK (status IN ('running', 'completed', 'failed', 'cancelled')),
    token_cost      INT DEFAULT 0,
    models_used     JSONB DEFAULT '[]',
    health_snapshot JSONB,
    summary         TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE kukuvaia.dream_recommendations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id       UUID NOT NULL REFERENCES kukuvaia.dream_reports(id) ON DELETE CASCADE,
    type            VARCHAR(50) NOT NULL,
    priority        VARCHAR(20) NOT NULL CHECK (priority IN ('critical', 'high', 'medium', 'low', 'informational')),
    description     TEXT NOT NULL,
    suggested_action TEXT,
    confidence      DECIMAL(3,2),
    evidence        JSONB DEFAULT '{}',
    status          VARCHAR(20) DEFAULT 'pending'
                    CHECK (status IN ('pending', 'accepted', 'rejected', 'expired')),
    resolved_at     TIMESTAMP,
    resolved_by     VARCHAR(255),
    rejection_reason TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_dream_reports_status ON kukuvaia.dream_reports(status, started_at DESC);
CREATE INDEX idx_dream_recs_pending ON kukuvaia.dream_recommendations(status) WHERE status = 'pending';
CREATE INDEX idx_dream_recs_report ON kukuvaia.dream_recommendations(report_id);

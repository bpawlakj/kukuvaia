-- Plans — task planning with step tracking, linked to user and session.
CREATE TABLE kukuvaia.plans (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(255) NOT NULL REFERENCES kukuvaia.sessions(id),
    user_id    VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    task       TEXT NOT NULL,
    steps      JSONB NOT NULL,
    status     VARCHAR(20) DEFAULT 'active'
               CHECK (status IN ('active', 'completed', 'abandoned')),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

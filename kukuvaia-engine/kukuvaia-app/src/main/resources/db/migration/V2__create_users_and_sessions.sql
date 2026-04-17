-- Users — lightweight identity (auth is external).
CREATE TABLE kukuvaia.users (
    id           VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255),
    preferences  JSONB DEFAULT '{}',
    created_at   TIMESTAMP DEFAULT NOW(),
    last_seen_at TIMESTAMP DEFAULT NOW()
);

-- Sessions — first-class entity linking conversations to users.
CREATE TABLE kukuvaia.sessions (
    id         VARCHAR(255) PRIMARY KEY,
    user_id    VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    name       VARCHAR(500),
    metadata   JSONB DEFAULT '{}',
    status     VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_sessions_user ON kukuvaia.sessions(user_id, updated_at DESC);

-- Conversations — one JSONB document per session (replaces row-per-message).
CREATE TABLE kukuvaia.conversations (
    session_id    VARCHAR(255) PRIMARY KEY REFERENCES kukuvaia.sessions(id),
    messages      JSONB NOT NULL DEFAULT '[]',
    summary       TEXT,
    message_count INT DEFAULT 0,
    token_count   INT DEFAULT 0,
    updated_at    TIMESTAMP DEFAULT NOW()
);

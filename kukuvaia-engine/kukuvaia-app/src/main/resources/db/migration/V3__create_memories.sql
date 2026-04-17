-- Memories — persistent cross-session knowledge with semantic search support.
CREATE TABLE kukuvaia.memories (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    session_id       VARCHAR(255) REFERENCES kukuvaia.sessions(id),
    memory_type      VARCHAR(20) NOT NULL DEFAULT 'semantic'
                     CHECK (memory_type IN ('episodic', 'semantic', 'procedural')),
    category         VARCHAR(50) NOT NULL
                     CHECK (category IN ('user', 'project', 'feedback', 'reference')),
    name             VARCHAR(255) NOT NULL,
    description      TEXT NOT NULL,
    content          TEXT NOT NULL,
    embedding        vector(1536),
    relevance_score  FLOAT DEFAULT 1.0,
    access_count     INT DEFAULT 0,
    last_accessed_at TIMESTAMP DEFAULT NOW(),
    expires_at       TIMESTAMP,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, name)
);

CREATE INDEX idx_memories_user_category ON kukuvaia.memories(user_id, category);
CREATE INDEX idx_memories_user_type ON kukuvaia.memories(user_id, memory_type);
CREATE INDEX idx_memories_relevance ON kukuvaia.memories(user_id, relevance_score DESC);
CREATE INDEX idx_memories_search ON kukuvaia.memories
    USING gin(to_tsvector('english', description || ' ' || content));
CREATE INDEX idx_memories_embedding ON kukuvaia.memories
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

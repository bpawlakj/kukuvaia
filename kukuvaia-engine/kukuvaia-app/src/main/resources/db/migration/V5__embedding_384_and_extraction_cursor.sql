-- Switch embedding dimension from 1536 (OpenAI) to 384 (all-MiniLM-L6-v2 local model).
-- Drop old index, alter column, recreate index.

DROP INDEX IF EXISTS kukuvaia.idx_memories_embedding;

ALTER TABLE kukuvaia.memories ALTER COLUMN embedding TYPE vector(384)
    USING NULL;

CREATE INDEX idx_memories_embedding ON kukuvaia.memories
    USING hnsw (embedding vector_cosine_ops);

-- Extraction cursor: tracks which messages have been processed for memory extraction.
ALTER TABLE kukuvaia.sessions ADD COLUMN IF NOT EXISTS extraction_cursor_idx INT DEFAULT 0;

-- Source tracking: whether memory was created explicitly (tool) or automatically (extraction).
ALTER TABLE kukuvaia.memories ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'explicit'
    CHECK (source IN ('explicit', 'extracted', 'consolidated'));

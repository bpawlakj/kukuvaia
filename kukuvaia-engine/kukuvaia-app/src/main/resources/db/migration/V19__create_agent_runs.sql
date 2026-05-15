-- Agent runs (P23 Phase A) — non-interactive agent invocations.
--
-- Peer to sessions, NOT a child. Records one execution by any non-human caller
-- (scheduler, webhook, API, agent-to-agent, CLI one-shot). No FK to users/sessions —
-- caller identity is captured in invoker_name + invoker_kind. See P23 invariants:
-- agent runs MUST NOT touch users / sessions / SPRING_AI_CHAT_MEMORY / kukuvaia.memories.
--
-- Embedding dimension matches kukuvaia.memories (384, all-MiniLM-L6-v2) so the two
-- vector spaces are comparable if a future plan needs cross-lookup.

CREATE TABLE kukuvaia.agent_runs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    thread_id          UUID,
    parent_run_id      UUID REFERENCES kukuvaia.agent_runs(id),

    invoker_name       VARCHAR(255) NOT NULL,
    invoker_kind       VARCHAR(50)  NOT NULL,

    input_type         VARCHAR(100) NOT NULL,
    input              JSONB NOT NULL,
    instructions       TEXT,
    persona_name       VARCHAR(100),
    task_class         VARCHAR(100),

    model              VARCHAR(100),
    status             VARCHAR(20) NOT NULL,
    error_code         VARCHAR(100),
    error_message      TEXT,

    output             JSONB,
    output_text        TEXT,
    embedding          vector(384),

    prompt_tokens      INT,
    completion_tokens  INT,
    total_tokens       INT GENERATED ALWAYS AS
                       (COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0)) STORED,

    queued_at          TIMESTAMPTZ,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    tags               TEXT[] NOT NULL DEFAULT '{}',
    severity           VARCHAR(20)
);

CREATE INDEX idx_ar_invoker_time  ON kukuvaia.agent_runs (invoker_name, created_at DESC);
CREATE INDEX idx_ar_input_type    ON kukuvaia.agent_runs (input_type, created_at DESC);
CREATE INDEX idx_ar_status        ON kukuvaia.agent_runs (status, created_at DESC);

CREATE INDEX idx_ar_thread        ON kukuvaia.agent_runs (thread_id, created_at)
    WHERE thread_id IS NOT NULL;
CREATE INDEX idx_ar_parent        ON kukuvaia.agent_runs (parent_run_id)
    WHERE parent_run_id IS NOT NULL;
CREATE INDEX idx_ar_severity      ON kukuvaia.agent_runs (severity, created_at DESC)
    WHERE severity IS NOT NULL;

CREATE INDEX idx_ar_embedding_cos ON kukuvaia.agent_runs
    USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_ar_tags          ON kukuvaia.agent_runs USING gin (tags);

COMMENT ON TABLE kukuvaia.agent_runs IS
    'Non-interactive agent invocations (P23). Peer to sessions — never tied to a user. '
    'Populated by HTTP entry point /api/agent-runs (sync or async); future scheduler / '
    'webhook will write here too. Invariant: no row in users/sessions/SPRING_AI_CHAT_MEMORY/'
    'kukuvaia.memories is created or modified as a side effect of any agent_runs operation.';

-- P21 Plan Registry & Composition
-- Adds name, phase, and persisted discovery_facts to plans + plan_links relation table.

ALTER TABLE kukuvaia.plans
    ADD COLUMN name             VARCHAR(120),
    ADD COLUMN phase            VARCHAR(20) DEFAULT 'approval'
        CHECK (phase IN ('discovery', 'drafting', 'approval', 'done', 'abandoned')),
    ADD COLUMN discovery_facts  JSONB DEFAULT '{}';

-- Backfill: derive short name from task for existing rows (first 60 chars).
UPDATE kukuvaia.plans SET name = LEFT(task, 60) WHERE name IS NULL;

-- Backfill: align phase with legacy status. 'active' previously meant "user-approved",
-- which maps to 'done' in the new phase vocabulary. 'draft' stays in APPROVAL phase
-- (user is still deciding) so /plan resume works on it.
UPDATE kukuvaia.plans SET phase = CASE
    WHEN status = 'active'    THEN 'done'
    WHEN status = 'completed' THEN 'done'
    WHEN status = 'abandoned' THEN 'abandoned'
    WHEN status = 'draft'     THEN 'approval'
    ELSE 'approval'
END;

CREATE INDEX idx_plans_user_phase ON kukuvaia.plans(user_id, phase, updated_at DESC);

CREATE TABLE kukuvaia.plan_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_plan_id  UUID NOT NULL REFERENCES kukuvaia.plans(id) ON DELETE CASCADE,
    target_plan_id  UUID NOT NULL REFERENCES kukuvaia.plans(id) ON DELETE CASCADE,
    relation        VARCHAR(30) NOT NULL
        CHECK (relation IN ('combines', 'derived_from', 'follows', 'alternative_to')),
    note            TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE (source_plan_id, target_plan_id, relation),
    CHECK (source_plan_id <> target_plan_id)
);

CREATE INDEX idx_plan_links_source ON kukuvaia.plan_links(source_plan_id);
CREATE INDEX idx_plan_links_target ON kukuvaia.plan_links(target_plan_id);

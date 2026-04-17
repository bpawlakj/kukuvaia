-- Harness engineering: user/group rule system for LLM context injection.
-- 5-level hierarchy: platform → group → user → project → session.

-- Groups (teams, departments)
CREATE TABLE kukuvaia.groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    parent_id   UUID REFERENCES kukuvaia.groups(id) ON DELETE SET NULL,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- User-group membership (users.id is VARCHAR(255), not UUID)
CREATE TABLE kukuvaia.user_groups (
    user_id     VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id) ON DELETE CASCADE,
    group_id    UUID NOT NULL REFERENCES kukuvaia.groups(id) ON DELETE CASCADE,
    role        VARCHAR(20) DEFAULT 'member' CHECK (role IN ('admin', 'member', 'viewer')),
    joined_at   TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (user_id, group_id)
);

-- Rule sets (named collections of rules with scope and ownership)
CREATE TABLE kukuvaia.rule_sets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    scope       VARCHAR(20) NOT NULL CHECK (scope IN ('platform', 'group', 'user', 'project', 'session')),
    owner_type  VARCHAR(20) CHECK (owner_type IN ('system', 'group', 'user')),
    owner_id    VARCHAR(255),
    priority    INT DEFAULT 0,
    enabled     BOOLEAN DEFAULT TRUE,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(name, owner_type, owner_id)
);

-- Individual rules within a set
CREATE TABLE kukuvaia.rules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_set_id UUID NOT NULL REFERENCES kukuvaia.rule_sets(id) ON DELETE CASCADE,
    key         VARCHAR(100) NOT NULL,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('instruction', 'constraint', 'context', 'preference', 'persona_modifier')),
    content     TEXT NOT NULL,
    tags        JSONB DEFAULT '[]',
    activation  JSONB DEFAULT '{"always": true}',
    priority    INT DEFAULT 0,
    enabled     BOOLEAN DEFAULT TRUE,
    version     INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(rule_set_id, key)
);

CREATE INDEX idx_rule_sets_owner ON kukuvaia.rule_sets(owner_type, owner_id);
CREATE INDEX idx_rule_sets_scope ON kukuvaia.rule_sets(scope) WHERE enabled = TRUE;
CREATE INDEX idx_rules_tags ON kukuvaia.rules USING gin(tags);
CREATE INDEX idx_rules_type ON kukuvaia.rules(type) WHERE enabled = TRUE;
CREATE INDEX idx_user_groups_user ON kukuvaia.user_groups(user_id);

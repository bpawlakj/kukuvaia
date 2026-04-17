-- Provider/model registry for dynamic multi-provider LLM routing.
-- Replaces static environment variable configuration with DB-driven registry.

-- Provider instances — each row is a configured LLM endpoint.
CREATE TABLE kukuvaia.providers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    type        VARCHAR(50) NOT NULL
                CHECK (type IN ('smartgate', 'openrouter', 'openai', 'anthropic', 'ollama', 'custom')),
    base_url    VARCHAR(500) NOT NULL,
    api_key_ref VARCHAR(255) NOT NULL,
    enabled     BOOLEAN DEFAULT TRUE,
    priority    INT DEFAULT 0,
    config      JSONB DEFAULT '{}',
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- Models — discovered or manually configured models within a provider.
CREATE TABLE kukuvaia.models (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id     UUID NOT NULL REFERENCES kukuvaia.providers(id) ON DELETE CASCADE,
    model_id        VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    capabilities    JSONB DEFAULT '[]',
    tier            VARCHAR(20) DEFAULT 'standard'
                    CHECK (tier IN ('economy', 'standard', 'premium', 'enterprise')),
    max_tokens      INT DEFAULT 4096,
    context_window  INT,
    enabled         BOOLEAN DEFAULT TRUE,
    config          JSONB DEFAULT '{}',
    discovered_at   TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(provider_id, model_id)
);

-- Model role assignments — which model serves which routing role.
CREATE TABLE kukuvaia.model_roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role        VARCHAR(50) NOT NULL UNIQUE,
    model_id    UUID NOT NULL REFERENCES kukuvaia.models(id) ON DELETE RESTRICT,
    description VARCHAR(500),
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_models_provider ON kukuvaia.models(provider_id);
CREATE INDEX idx_models_tier ON kukuvaia.models(tier) WHERE enabled = TRUE;
CREATE INDEX idx_models_capabilities ON kukuvaia.models USING gin(capabilities);

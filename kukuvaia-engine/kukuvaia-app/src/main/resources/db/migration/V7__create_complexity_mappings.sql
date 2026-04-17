-- Complexity-to-role mappings for automatic per-action model tier assignment.
-- Maps TaskComplexity enum values to routing roles from model_roles table.

CREATE TABLE kukuvaia.complexity_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    complexity      VARCHAR(30) NOT NULL UNIQUE,
    role            VARCHAR(50) NOT NULL,
    description     VARCHAR(500),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Seed defaults matching TaskComplexity enum
INSERT INTO kukuvaia.complexity_mappings (complexity, role, description) VALUES
    ('EXTRACTION', 'worker', 'Structured data extraction, parsing'),
    ('TRANSFORMATION', 'worker', 'Format conversion, mapping'),
    ('CLASSIFICATION', 'worker', 'Categorization, labeling'),
    ('RETRIEVAL', 'worker', 'Search, lookup, filtering'),
    ('ANALYSIS', 'supervisor', 'Pattern finding, reasoning'),
    ('GENERATION', 'supervisor', 'Creative content, code'),
    ('SYNTHESIS', 'supervisor', 'Multi-source merging, summarization'),
    ('STRATEGY', 'advisor', 'Planning, architecture decisions'),
    ('EVALUATION', 'supervisor', 'Quality assessment, review');

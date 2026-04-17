# Analysis: Harness Engineering — User/Group Rule System

**Created**: 2026-04-12
**Status**: Concept analysis

## Concept

Harness engineering = defining rules, context, and behavioral constraints so that LLMs know who they're working with and how to behave before the first message. Kukuvaia needs a system where:

- Users and groups have **persistent rule sets** (coding standards, preferences, domain knowledge)
- Rules are **composable and inheritable** (global → group → user → project → session)
- Rules are **stored durably** (filesystem for self-hosted, S3 for cloud/team)
- Rules are **injected into LLM context** automatically (system prompt, advisor chain)
- Rules are **manageable via API** (future admin UI)

## Existing Patterns (What Works)

Analysis of harness engineering across the user's projects reveals a mature, multi-layered pattern:

### ai-devkit Pattern (Portable Foundation)

```
ai-devkit/
├── claude/
│   ├── rules/              # 11 language-specific rule files
│   │   ├── java.md         # Auto-activated when editing *.java
│   │   ├── python.md       # Auto-activated when editing *.py
│   │   ├── security.md     # Always active (cross-cutting)
│   │   └── ...
│   ├── commands/           # 15 slash command prompts
│   ├── agents/             # 5 specialized agent definitions
│   └── settings.template.json  # Hooks, MCP servers, plugins
└── copilot/
    └── instructions/       # Parallel rules for GitHub Copilot
```

**Key insight**: Rules are language-specific and auto-activated by file pattern. Portable across projects.

### Claude Code Pattern (Layered Hierarchy)

```
Layer 1 (Global):     ~/.claude/CLAUDE.md          → workflow principles, admin rules
Layer 2 (Global):     ~/.claude/rules/*.md          → language standards (java, python, etc.)
Layer 3 (Project):    project/CLAUDE.md             → project-specific vision, architecture
Layer 4 (Project):    project/.maister/docs/        → team coding standards
Layer 5 (Directory):  project/dir/AGENTS.md         → boundary definitions per scope
Layer 6 (Automation): project/.claude/settings.json → hooks, formatters, safety guards
```

**Key insight**: Rules compose through layers. Global applies everywhere, project narrows, directory scopes further.

### kukuvaia Extensibility (Current)

```
.kukuvaia/
├── rules/*.md          → always-on constraints → injected into system prompt
├── skills/*/SKILL.md   → on-demand procedures → activated by /skill command
├── commands/*.yaml     → user-defined slash commands
├── personas/*.yaml     → custom personas (system prompt + tool filter)
└── settings.yaml       → config overrides
```

**Key insight**: kukuvaia already has the directory-level extensibility. What's missing is user identity, group membership, rule composition, and durable storage.

## Gap Analysis

| Feature | ai-devkit/Claude Code | kukuvaia (current) | kukuvaia (needed) |
|---------|----------------------|-------------------|-------------------|
| Language-specific rules | Auto-activated by file pattern | `.kukuvaia/rules/` (manual) | Auto-activate by context |
| User identity | `~/.claude/CLAUDE.md` (implicit) | None | User profile + preferences in DB |
| Group rules | None (single developer) | None | Group-based rule sets |
| Rule inheritance | Layer 1-6 hierarchy | Flat (project only) | Global → group → user → project → session |
| Storage backend | Filesystem only | Filesystem only | Filesystem + S3 + DB |
| API management | None (edit files manually) | None | CRUD endpoints |
| Rule versioning | Git (implicit) | Git (implicit) | Versioned in DB with history |
| Cross-tool portability | ai-devkit → Claude Code + Copilot | kukuvaia only | Export to Claude Code / Copilot / Cursor format |

## Architecture Design

### Rule Hierarchy (5 Levels)

```
┌─────────────────────────────────────────┐
│ Level 1: Platform Defaults               │  Built-in, shipped with kukuvaia
│ "Be helpful, be safe, follow standards"  │  Immutable, always present
└─────────────────────┬───────────────────┘
                      │ inherits
┌─────────────────────▼───────────────────┐
│ Level 2: Organization / Group Rules      │  Shared across team
│ "Java 21+, Spring Boot, JUnit 5"        │  Stored in DB or S3
│ "Always use AssertJ, never Hamcrest"     │  Managed via API
└─────────────────────┬───────────────────┘
                      │ inherits + overrides
┌─────────────────────▼───────────────────┐
│ Level 3: User Rules                      │  Personal preferences
│ "I'm a senior dev, skip basic explains"  │  Stored in DB
│ "I prefer Polish, but docs in English"   │  Managed via API
└─────────────────────┬───────────────────┘
                      │ inherits + overrides
┌─────────────────────▼───────────────────┐
│ Level 4: Project Rules                   │  Per-project conventions
│ .kukuvaia/rules/*.md                     │  Stored in filesystem
│ CLAUDE.md, .maister/docs/standards/      │  Git-versioned
└─────────────────────┬───────────────────┘
                      │ inherits + overrides
┌─────────────────────▼───────────────────┐
│ Level 5: Session Rules                   │  Temporary, per-conversation
│ "In this session, focus on performance"  │  In-memory only
│ "Use Go idioms, not Java patterns"       │  Discarded after session
└─────────────────────────────────────────┘
```

**Resolution**: Lower level overrides higher. If group says "use Hamcrest" but user says "use AssertJ", AssertJ wins. Session rules override everything temporarily.

### Data Model

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   groups     │     │    users     │     │  rule_sets   │
│──────────────│     │──────────────│     │──────────────│
│ id           │◄──┐ │ id           │  ┌─►│ id           │
│ name         │   │ │ display_name │  │  │ name         │
│ description  │   │ │ preferences  │  │  │ description  │
│ parent_id FK │   │ │ created_at   │  │  │ scope        │ ← group/user/project/session
│ created_at   │   │ └──────────────┘  │  │ owner_type   │ ← group/user
└──────────────┘   │                   │  │ owner_id     │ ← FK to group or user
                   │ ┌──────────────┐  │  │ priority     │
                   │ │ user_groups  │  │  │ enabled      │
                   │ │──────────────│  │  │ created_at   │
                   └─│ group_id  FK │  │  └──────────────┘
                     │ user_id   FK │  │         │
                     │ role         │  │         │ 1:N
                     └──────────────┘  │  ┌──────▼───────┐
                                       │  │    rules     │
                                       │  │──────────────│
                                       └──│ rule_set_id  │
                                          │ key          │ ← unique within set
                                          │ type         │ ← instruction/constraint/context/preference
                                          │ content      │ ← markdown text
                                          │ tags         │ ← JSONB ["java","testing"]
                                          │ activation   │ ← JSONB (file patterns, intents, etc.)
                                          │ priority     │ ← ordering within set
                                          │ enabled      │
                                          │ version      │
                                          │ created_at   │
                                          └──────────────┘
```

### Rule Types

| Type | Purpose | Example | When Injected |
|------|---------|---------|---------------|
| `instruction` | How to behave | "Always use constructor injection" | System prompt |
| `constraint` | What NOT to do | "Never use field @Autowired" | System prompt |
| `context` | Background knowledge | "This team uses Spring Boot 3.4" | System prompt |
| `preference` | Soft guidance | "Prefer records for DTOs" | System prompt |
| `activation` | Conditional rule | "When editing *.java, apply Java rules" | Advisor detects file context |
| `persona_modifier` | Adjusts persona | "Be concise, skip explanations" | Persona system prompt |

### Rule Activation

Rules can be always-on or conditional:

```json
{
  "activation": {
    "always": true
  }
}

{
  "activation": {
    "file_patterns": ["*.java", "*.kt"],
    "intents": ["DOCUMENT_WRITE", "ANALYSIS"],
    "tools": ["memory_save", "planning_*"]
  }
}

{
  "activation": {
    "keywords": ["test", "junit", "coverage"],
    "personas": ["developer", "reviewer"]
  }
}
```

**IntentDetectionAdvisor** and **ModelRoutingAdvisor** already classify intent — activation rules piggyback on this classification.

### Storage Backends

```
┌─────────────────────────────────┐
│        RuleStorageService       │  ← abstraction layer
│  (interface)                    │
└──────┬──────────┬───────────────┘
       │          │
┌──────▼─────┐ ┌─▼──────────────┐
│ PostgreSQL │ │    S3 Backend   │
│  Backend   │ │                 │
│            │ │ s3://bucket/    │
│ kukuvaia.  │ │   rules/        │
│ rule_sets  │ │   groups/       │
│ rules      │ │   users/        │
└────────────┘ └─────────────────┘
  self-hosted    team/cloud deploy
```

**Self-hosted** (default): Rules in PostgreSQL. Simple, no extra infra.

**Team/cloud** (optional): Rules in S3 bucket. Shared across kukuvaia instances. Synced to local DB cache on startup and on-change (S3 event notification or polling).

Configuration:
```yaml
kukuvaia:
  harness:
    storage: database          # or "s3"
    s3:
      bucket: kukuvaia-harness
      prefix: rules/
      region: eu-west-1
```

## Database Schema

```sql
-- Groups (teams, departments, organizations)
CREATE TABLE kukuvaia.groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    parent_id   UUID REFERENCES kukuvaia.groups(id) ON DELETE SET NULL,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

-- User-group membership
CREATE TABLE kukuvaia.user_groups (
    user_id     UUID NOT NULL REFERENCES kukuvaia.users(id) ON DELETE CASCADE,
    group_id    UUID NOT NULL REFERENCES kukuvaia.groups(id) ON DELETE CASCADE,
    role        VARCHAR(20) DEFAULT 'member' CHECK (role IN ('admin','member','viewer')),
    joined_at   TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (user_id, group_id)
);

-- Rule sets (named collections of rules)
CREATE TABLE kukuvaia.rule_sets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    scope       VARCHAR(20) NOT NULL CHECK (scope IN ('platform','group','user','project','session')),
    owner_type  VARCHAR(20) CHECK (owner_type IN ('system','group','user')),
    owner_id    UUID,               -- FK to groups or users depending on owner_type
    priority    INT DEFAULT 0,      -- higher = applied later (can override)
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
    type        VARCHAR(20) NOT NULL CHECK (type IN ('instruction','constraint','context','preference','activation','persona_modifier')),
    content     TEXT NOT NULL,           -- markdown content
    tags        JSONB DEFAULT '[]',      -- ["java","spring","testing"]
    activation  JSONB DEFAULT '{"always": true}',
    priority    INT DEFAULT 0,
    enabled     BOOLEAN DEFAULT TRUE,
    version     INT DEFAULT 1,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(rule_set_id, key)
);

CREATE INDEX idx_rules_tags ON kukuvaia.rules USING gin(tags);
CREATE INDEX idx_rules_activation ON kukuvaia.rules USING gin(activation);
CREATE INDEX idx_rule_sets_owner ON kukuvaia.rule_sets(owner_type, owner_id);
```

## How Rules Reach the LLM

### HarnessAdvisor (Spring AI BaseAdvisor)

New advisor in the chain that compiles applicable rules into the system prompt:

```
Request arrives
    │
    ▼
HarnessAdvisor.before()
    │
    ├── 1. Identify user (from session → user_id)
    ├── 2. Resolve user's groups
    ├── 3. Collect rule sets by priority:
    │       Platform defaults (scope=platform)
    │       → Group rules (scope=group, for each user group)
    │       → User rules (scope=user, for this user)
    │       → Project rules (scope=project, from .kukuvaia/rules/)
    │       → Session rules (scope=session, from session context)
    │
    ├── 4. Filter by activation conditions:
    │       Check file patterns, intents, tools, keywords
    │       Remove inactive rules
    │
    ├── 5. Resolve conflicts (lower scope overrides higher):
    │       If group says X and user says NOT X → NOT X wins
    │       Same key at different scopes → lowest scope wins
    │
    ├── 6. Compile into system prompt section:
    │       "## Active Rules\n{compiled_rules}"
    │
    └── 7. Inject into request as system message
            request.mutate().system(existingPrompt + compiledRules)
```

### Advisor Chain Position

```
ProviderAuditLog         (HIGHEST_PRECEDENCE)     — log provider
IntentDetectionAdvisor   (HIGHEST_PRECEDENCE+10)  — classify intent
ModelRoutingAdvisor      (HIGHEST_PRECEDENCE+12)  — select model
HarnessAdvisor           (HIGHEST_PRECEDENCE+14)  — inject rules ← NEW
SmartMemoryAdvisor       (HIGHEST_PRECEDENCE+15)  — inject memories
ToolResultSanitizing     (...)                     — sanitize tool results
MessageChatMemoryAdvisor (...)                     — inject history
```

HarnessAdvisor runs AFTER intent detection (needs intent for activation rules) and BEFORE memory (rules establish context, memory provides specifics).

### Caching

Rule compilation is cached per-user with TTL:

```java
ConcurrentHashMap<UUID, CachedHarness> harnessCache;
// key: user_id
// value: compiled rules + timestamp
// TTL: 5 minutes (rules change rarely, no need to recompile every request)
// Invalidated: on rule CRUD via API
```

## API Endpoints

### Group Management
```
POST   /api/groups                     — create group
GET    /api/groups                     — list groups
GET    /api/groups/{id}                — get group with members
PUT    /api/groups/{id}                — update group
DELETE /api/groups/{id}                — delete group
POST   /api/groups/{id}/members        — add user to group
DELETE /api/groups/{id}/members/{uid}   — remove user from group
```

### Rule Set Management
```
POST   /api/rule-sets                  — create rule set
GET    /api/rule-sets                  — list rule sets (filter: scope, owner)
GET    /api/rule-sets/{id}             — get rule set with all rules
PUT    /api/rule-sets/{id}             — update rule set metadata
DELETE /api/rule-sets/{id}             — delete rule set

POST   /api/rule-sets/{id}/rules       — add rule to set
GET    /api/rule-sets/{id}/rules       — list rules in set
PUT    /api/rule-sets/{id}/rules/{rid} — update rule
DELETE /api/rule-sets/{id}/rules/{rid} — delete rule
```

### Rule Resolution (Read-Only)
```
GET    /api/harness/resolve            — resolve all active rules for current user
GET    /api/harness/resolve?intent=X   — resolve with intent filter
GET    /api/harness/preview            — preview what system prompt the LLM sees
```

### Import/Export
```
POST   /api/harness/import             — import rules from file (markdown, YAML)
GET    /api/harness/export             — export rules to file
POST   /api/harness/import/claude-code — import from Claude Code CLAUDE.md format
GET    /api/harness/export/claude-code — export to Claude Code CLAUDE.md format
```

## Example: Setting Up a Java Team

### 1. Create group
```json
POST /api/groups
{ "name": "java-backend-team", "description": "Spring Boot backend developers" }
```

### 2. Create rule set for the group
```json
POST /api/rule-sets
{
  "name": "java-spring-standards",
  "description": "Java 21 + Spring Boot 3.x coding standards",
  "scope": "group",
  "ownerType": "group",
  "ownerId": "uuid-of-java-backend-team"
}
```

### 3. Add rules
```json
POST /api/rule-sets/{id}/rules
{
  "key": "dependency-injection",
  "type": "constraint",
  "content": "Constructor injection only. All fields final. Never use @Autowired on fields.",
  "tags": ["java", "spring"],
  "activation": { "file_patterns": ["*.java"] }
}
```

```json
POST /api/rule-sets/{id}/rules
{
  "key": "testing-framework",
  "type": "instruction",
  "content": "Use JUnit 5 + AssertJ. Test naming: methodName_scenario_expectedBehavior(). 80%+ coverage for security-critical code.",
  "tags": ["java", "testing"],
  "activation": { "file_patterns": ["*Test.java", "*Tests.java"] }
}
```

```json
POST /api/rule-sets/{id}/rules
{
  "key": "team-context",
  "type": "context",
  "content": "This team works on kukuvaia-engine (Spring Boot 3.4, Spring AI 1.1, Java 21). The codebase uses sealed interfaces for OutputBlocks, records for DTOs, and JdbcTemplate for data access (no JPA).",
  "tags": ["java", "spring", "kukuvaia"],
  "activation": { "always": true }
}
```

### 4. Add user-level override
```json
POST /api/rule-sets
{
  "name": "bartek-preferences",
  "scope": "user",
  "ownerType": "user",
  "ownerId": "uuid-of-bartek"
}

POST /api/rule-sets/{id}/rules
{
  "key": "communication-style",
  "type": "persona_modifier",
  "content": "Senior developer. Skip basic explanations. Be concise. Communicate in Polish when chatting, but all code comments and documentation in English.",
  "activation": { "always": true }
}
```

### 5. Result: What LLM Sees

When Bartek sends a message while editing a Java file:

```
## System Context

### Team Standards (java-backend-team)
- Constructor injection only. All fields final. Never use @Autowired on fields.
- Use JUnit 5 + AssertJ. Test naming: methodName_scenario_expectedBehavior().
- This team works on kukuvaia-engine (Spring Boot 3.4, Spring AI 1.1, Java 21).

### User Preferences (bartek)
- Senior developer. Skip basic explanations. Be concise.
- Communicate in Polish when chatting, documentation in English.

### Project Rules (kukuvaia)
[from .kukuvaia/rules/*.md — loaded from filesystem]

### Session Context
[any session-specific rules set during this conversation]
```

## Cross-Tool Portability

### Import from Claude Code Format

Parse `CLAUDE.md` + `rules/*.md` into kukuvaia rule sets:

```
~/.claude/CLAUDE.md          → platform-level rule set
~/.claude/rules/java.md      → group rule set (tagged: java)
~/.claude/rules/security.md  → group rule set (tagged: security)
project/CLAUDE.md            → project-level rule set
```

### Export to Claude Code Format

Generate `CLAUDE.md` from resolved rules:

```
kukuvaia resolved rules → CLAUDE.md (merged markdown)
kukuvaia group "java"   → rules/java.md
kukuvaia group "python" → rules/python.md
```

### Export to Other Formats

```
kukuvaia rules → .cursorrules          (Cursor)
kukuvaia rules → .github/copilot-instructions.md (Copilot)
kukuvaia rules → AGENTS.md             (Codex/generic)
```

This makes kukuvaia a **central rule management system** that distributes harness to any AI coding tool.

## Relationship to Existing kukuvaia Features

| Feature | How Harness Integrates |
|---------|----------------------|
| **Personas** | `persona_modifier` rules adjust persona behavior per user/group |
| **Memory** | Rules provide stable context; memory provides dynamic knowledge. Complementary. |
| **Dreaming** | Config audit dream task validates rule consistency |
| **Provider registry** | Group rules can specify preferred providers/models |
| **Sub-agents** | Sub-agent prompts inherit active rules (filtered by activation) |
| **Embabel agents** | Agent actions can read active rules from context |
| **.kukuvaia/ directory** | Filesystem rules become project-scope rule sets (auto-imported) |

## Relationship to Existing User System

kukuvaia already has `kukuvaia.users` table (id, display_name, preferences JSONB). The harness system extends this with groups and rule ownership. No schema conflict — additive only.

## Implementation Phases

### Phase A: Database Schema + Rule CRUD
- Flyway migration for groups, user_groups, rule_sets, rules tables
- Repositories (JdbcTemplate)
- REST API for rule management
- No runtime effect yet

### Phase B: HarnessAdvisor
- New Spring AI advisor that compiles and injects rules
- Rule resolution logic (hierarchy, activation, conflict resolution)
- Cache with TTL and invalidation
- Wire into advisor chain

### Phase C: .kukuvaia/ Integration
- Auto-import `.kukuvaia/rules/*.md` as project-scope rule sets
- Sync on startup and on file change (WatchService)
- Merge filesystem rules with DB rules in resolution

### Phase D: Import/Export
- Parse Claude Code CLAUDE.md format
- Generate CLAUDE.md / .cursorrules / copilot-instructions.md
- Bulk import/export via API

### Phase E: S3 Backend
- S3 storage adapter for team/cloud deployment
- Sync to local DB cache
- Event-driven refresh (S3 notifications)

## Cost and Performance

- Rule resolution: ~1-5ms (DB query + cache hit for subsequent requests)
- Cache: ConcurrentHashMap with 5-minute TTL per user
- No LLM calls — rules are injected as static text into system prompt
- System prompt size: typically 500-2000 tokens for compiled rules (well within context window)
- No Redis needed — JVM cache sufficient for single instance

## Risks

| Risk | Mitigation |
|------|-----------|
| System prompt bloat (too many rules) | Token budget per scope level, priority-based truncation |
| Conflicting rules across levels | Clear resolution: lower scope overrides higher, warn on conflict |
| Stale rules in cache | 5-min TTL + explicit invalidation on CRUD |
| Rule explosion (hundreds of rules) | Tags + activation filtering ensures only relevant rules are injected |
| Import format mismatches | Validate on import, preview before applying |

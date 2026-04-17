# Kukuvaia Implementation Plan

**Date:** 2026-04-06
**Status:** Active
**Supersedes:** `kukuvaia-agent-implementation.md` (v2, Python/FastAPI — archived, superseded by Java/Spring Boot)
**Analysis:** `docs/analyzes/agent-capabilities-gap-analysis.md` (source research for this plan)

## Architecture Summary

```
kukuvaia-server (Java 21 / Spring Boot 3.x / Spring AI 1.x)
  │
  ├── security/          ✅ 8 classes — COMPLETE
  ├── agent/daemon/      ✅ 7 classes — COMPLETE
  ├── agent/subagent/    ✅ 4 classes — COMPLETE (tool registry TODO)
  ├── provider/          ✅ 2 classes — COMPLETE
  ├── api/webhook/       ✅ 1 class  — COMPLETE
  │
  ├── config/            ⬜ ChatClient, Memory, Tools beans
  ├── tools/             ⬜ @McpTool implementations (data + document + memory + planning + verification)
  ├── agent/core/        ⬜ AgentService, CommandRouter, PersonaService
  ├── commands/          ⬜ Slash command handlers
  ├── extensions/        ⬜ .kukuvaia/ loader (rules, skills, commands, personas)
  ├── output/            ⬜ OutputBlock hierarchy (typed chunks)
  ├── api/               ⬜ ChatController, SessionController, CommandController
  └── advisors/          ⬜ Memory, Intent, Budget, Hook advisors
```

**Key decision (v2 → v3):** Agent runs Python with separate MCP Gateway → superseded by single Java/Spring Boot server with in-process @McpTool. Tools live in-process — zero HTTP overhead. If scaling needed, extract tools to separate MCP server without changing agent logic.

---

## What's Already Implemented

### Security (8 classes — production-ready)

| Class | Finding | What It Does |
|-------|---------|-------------|
| `ApiAuthFilter` | #3 | Bearer token + JWT auth for Web API |
| `WebhookAuthFilter` | #4 | HMAC-SHA256 signature verification (GitHub-style) |
| `WebhookRateLimiter` | #10 | Sliding window rate limiting per IP |
| `PayloadSanitizer` | #5, #12 | Extracts structured fields, blocks injection patterns |
| `ErrorSanitizer` | #11 | Strips sensitive data from error responses |
| `ToolResultSanitizingAdvisor` | #8 | Anti-injection in tool results (advisor) |
| `CredentialsFileGuard` | #22 | Validates ~/.kukuvaia/credentials.json permissions |
| `SecurityConfig` | — | Central Spring Security configuration |

### Daemon (7 classes — production-ready)

| Class | What It Does |
|-------|-------------|
| `DaemonAgentService` | Orchestrates background tasks via sub-agents |
| `DaemonBudgetGuard` | Daily token budget with 80% alert threshold |
| `DaemonScheduleGuard` | Skip-if-running semantics (prevent cron overlap) |
| `PgNotifyDebouncer` | Batches PG NOTIFY events (5s window, max 100) |
| `NotificationSanitizer` | Sanitizes results before external sinks |
| `DaemonTaskResult` | Audit trail record |
| `ExecutionContext` | INTERACTIVE vs DAEMON routing enum |

### Sub-Agents (4 classes — production-ready, tool registry TODO)

| Class | What It Does |
|-------|-------------|
| `SubAgentFactory` | Creates isolated ChatClient per specialist. **TODO line 99:** `.defaultTools(resolveToolObjects(safeTools))` |
| `SubAgentGuard` | Depth=1, tool filtering, prompt hardening, blocks delegation tools |
| `SubAgentSpec` | Record: name, description, provider, systemPrompt, tools, model, maxTokens, maxToolRounds, temperature, timeout |
| `SubAgentSpecLoader` | Loads from classpath:specialists/*.yaml + .kukuvaia/specialists/*.yaml |

### Providers (2 classes — production-ready)

| Class | What It Does |
|-------|-------------|
| `LlmProviderService` | Context-aware routing: Interactive → Copilot, Daemon → SmartGate |
| `ProviderAuditLog` | Advisor logging provider, model, tokens per LLM call |

### Webhooks (1 class — production-ready)

| Class | What It Does |
|-------|-------------|
| `WebhookController` | POST /api/webhooks/ci, /api/webhooks/alert → DaemonAgentService |

---

## Implementation Phases

### Phase 1: Foundation (Agent Core + Data Tools)

**Goal:** Agent can receive messages, route them, call data tools, return responses. Minimum viable agent.

#### 1.1 Spring Configuration

**Package:** `ai.kukuvaia.config`

| File | What It Does |
|------|-------------|
| `ChatClientConfig.java` | ChatClient bean with advisor chain. Injects: ToolResultSanitizingAdvisor, ProviderAuditLog, MessageChatMemoryAdvisor, ToolCallAdvisor |
| `MemoryConfig.java` | JdbcChatMemoryRepository bean + MessageWindowChatMemory (initial: sliding window, Phase 3 upgrades to hierarchical) |
| `ToolRegistryConfig.java` | Resolves @McpTool beans, builds tool catalog, exposes tool metadata for SubAgentFactory |

**Integration with existing security:**
- `ChatClientConfig` includes `ToolResultSanitizingAdvisor` (already implemented) in advisor chain
- `ProviderAuditLog` (already implemented) runs as first advisor

#### 1.2 Data Tools (@McpTool)

**Package:** `ai.kukuvaia.tools`

These tools are documented in `kukuvaia-server/CLAUDE.md` but not yet implemented.

| File | Tools | Backend |
|------|-------|---------|
| `PostgresTools.java` | `get_outline`, `get_classifications`, `get_groups`, `search_items`, `get_outline_stats` | JdbcTemplate → kukuvaia_data schema |
| `MongoTools.java` | `get_outline_raw`, `get_sections`, `get_content_items` | MongoClient → etsl database |
| `AuthorsuiteTools.java` | `run_validation`, `get_validation_report` | HTTP client → Authorsuite API |

**Security compatibility:**
- All tools use parameterized queries (no SQL injection by design)
- Tool names go into `SubAgentGuard.filterTools()` — persona tool_filter applies automatically
- `ToolResultSanitizingAdvisor` treats all tool results as data — no prompt injection from DB content

#### 1.3 Agent Core

**Package:** `ai.kukuvaia.agent`

| File | What It Does |
|------|-------------|
| `AgentService.java` | Chat orchestration: receives message → builds prompt → ChatClient.call() → streams OutputBlocks |
| `CommandRouter.java` | Routes input: `/command` → CommandRegistry, free text → ChatClient agent loop |
| `PersonaService.java` | Loads persona YAML, manages active persona per session, provides system prompt + tool filter |

#### 1.4 Output Protocol

**Package:** `ai.kukuvaia.output`

```java
public sealed interface OutputBlock permits
    TextBlock, TableBlock, CodeBlock, ProgressBlock {}

public record TextBlock(String content, String style) implements OutputBlock {}
public record TableBlock(String title, List<String> headers,
                         List<List<String>> rows) implements OutputBlock {}
public record CodeBlock(String content, String language) implements OutputBlock {}
public record ProgressBlock(String label, int current, int total) implements OutputBlock {}
```

#### 1.5 Web API

**Package:** `ai.kukuvaia.api`

| File | Endpoints |
|------|----------|
| `ChatController.java` | `POST /api/chat` → SSE stream of OutputBlocks |
| `SessionController.java` | `GET /api/sessions`, `GET /api/sessions/{id}`, `DELETE /api/sessions/{id}` |
| `CommandController.java` | `POST /api/commands/{cmd}` → deterministic dispatch |

**Security compatibility:**
- All endpoints protected by `ApiAuthFilter` (already implemented in SecurityConfig)
- Error responses sanitized by `ErrorSanitizer` (already implemented)

#### 1.6 Slash Commands

**Package:** `ai.kukuvaia.commands`

| File | Command | Type |
|------|---------|------|
| `HelpCommand.java` | `/help` | Deterministic |
| `ValidateCommand.java` | `/validate {outlineId}` | Deterministic → @McpTool |
| `SearchCommand.java` | `/search {query}` | Deterministic → @McpTool |
| `ModelCommand.java` | `/model [list\|name]` | Deterministic |
| `LoginCommand.java` | `/login [github\|smartgate\|status\|logout]` | Deterministic |

#### 1.7 Tool Registry Resolution (SubAgentFactory TODO)

Complete the TODO on `SubAgentFactory.java:99`:

```java
// Current:
// TODO: .defaultTools(resolveToolObjects(safeTools)) — when tool registry is implemented

// Implementation:
private final ToolRegistry toolRegistry; // injected

private List<Object> resolveToolObjects(Set<String> toolNames) {
    return toolNames.stream()
        .map(toolRegistry::resolve)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
}
```

`ToolRegistry` is a bean that collects all @McpTool-annotated components and indexes them by tool name.

**Security compatibility:**
- `SubAgentGuard.filterTools()` produces the safe tool name set
- `ToolRegistry.resolve()` only returns tools by exact name match
- Sub-agents get only the tools approved by guard + persona intersection

#### Phase 1 Checklist

- [x] `ChatClientConfig` — ChatClient bean with advisor chain
- [x] `MemoryConfig` — MessageWindowChatMemory + JdbcChatMemoryRepository (auto-configured)
- [x] `ToolRegistryConfig` — Tool catalog from @Tool beans
- [x] `PostgresTools` — 5 data tools
- [x] `MongoTools` — REMOVED (replaced by external ETSL MCP Server via MCP Client)
- [x] `AuthorsuiteTools` — 2 data tools
- [x] `AgentService` — Chat orchestration
- [x] `CommandRouter` — Slash commands + agent loop routing
- [x] `PersonaService` — Persona YAML loading + tool filtering
- [x] `OutputBlock` — Sealed interface hierarchy (4 types)
- [x] `ChatController` — SSE streaming endpoint
- [x] `SessionController` — Session CRUD
- [x] `CommandController` — Command dispatch endpoint
- [x] Slash commands: `/help`, `/validate`, `/search`, `/model`, `/login`
- [x] `ToolRegistry` — Resolve @Tool beans by name (ToolRegistryConfig)
- [x] Complete SubAgentFactory TODO — wired ToolRegistryConfig for tool resolution
- [ ] Integration test: send message → get tool-use response → stream OutputBlocks

---

### Phase 2: Document Agent Capabilities

**Goal:** Agent can read/write/search documents, remember across sessions, plan work, verify quality. This is what transforms a "chatbot with data access" into an "intelligent document agent."

#### 2.1 Document File Tools

**Package:** `ai.kukuvaia.tools`

| File | Tools | Purpose |
|------|-------|---------|
| `DocumentTools.java` | `read_document`, `write_document`, `edit_document`, `search_documents`, `find_documents` | File operations within sandboxed workspace |

**Workspace sandbox:**
- Root: configurable per user/session (`kukuvaia.workspace.root`)
- Path traversal prevention: `normalize()` + `startsWith(root)` check
- Symlink escape prevention: `NOFOLLOW_LINKS`
- File type whitelist: `.md`, `.yaml`, `.json`, `.xml`, `.csv`, `.txt`, `.html`
- Max file size: 5 MB
- Automatic backup before overwrite (`.bak`)

**Security compatibility:**
- Path traversal blocked at tool level (defense in depth — SubAgentGuard also filters tool access)
- Document tools go through `ToolResultSanitizingAdvisor` — content from files treated as data
- Tool names added to persona `tool_filter` lists for access control

**Tool result persistence (from Claude Code):**
- If tool result exceeds `maxResultChars` (8000), save to workspace and return pointer
- Prevents context window blowout from large search results

**File state cache:**
- LRU cache (max 20 entries) for repeated reads in verify loops
- Cache key: `path:offset:limit`
- Invalidated on write/edit to same path

```java
@Component
public class DocumentTools {

    private final Path workspaceRoot;
    private final FileStateCache fileCache;
    private static final int MAX_RESULT_CHARS = 8000;
    private static final Set<String> ALLOWED_EXTENSIONS =
        Set.of("md", "yaml", "json", "xml", "csv", "txt", "html");

    @McpTool(name = "read_document", ...)
    @McpTool(name = "write_document", ...)
    @McpTool(name = "edit_document", ...)
    @McpTool(name = "search_documents", ...)
    @McpTool(name = "find_documents", ...)
}
```

Full implementation: see `docs/analyzes/agent-capabilities-gap-analysis.md` section 1.

#### 2.2 Persistent Memory

**Package:** `ai.kukuvaia.tools` (tools) + `ai.kukuvaia.advisors` (auto-injection + auto-extraction)

**Database schema:**

```sql
-- Migration: V2__create_agent_memory.sql
CREATE TABLE kukuvaia_agent.agent_memory (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(255) NOT NULL,
    category    VARCHAR(50) NOT NULL CHECK (category IN ('user', 'project', 'feedback', 'reference')),
    name        VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, name)
);

CREATE INDEX idx_memory_user_category ON kukuvaia_agent.agent_memory(user_id, category);
CREATE INDEX idx_memory_search ON kukuvaia_agent.agent_memory
    USING gin(to_tsvector('english', description || ' ' || content));
```

**Components:**

| File | Type | What It Does |
|------|------|-------------|
| `MemoryTools.java` | @McpTool | `save_memory`, `search_memories`, `list_memories`, `delete_memory` — explicit agent-initiated memory |
| `PersistentMemoryAdvisor.java` | Advisor | Loads user+feedback memories at conversation start, injects into system prompt |
| `MemoryExtractionAdvisor.java` | Advisor | Post-response async extraction of preferences, corrections, facts (lightweight model) |
| `MemoryRepository.java` | Repository | CRUD for agent_memory table, full-text search |

**Dual memory strategy:**
1. **Automatic** (MemoryExtractionAdvisor): runs after every turn, catches what agent misses
2. **Explicit** (MemoryTools): agent deliberately saves structured knowledge

**Security compatibility:**
- Memory tools go through `SubAgentGuard.filterTools()` — sub-agents can be restricted from memory writes
- `PersistentMemoryAdvisor` runs after `ToolResultSanitizingAdvisor` in advisor chain — memory content treated as context, not instructions
- Memory isolation per `user_id` — no cross-user memory access

Full implementation: see analysis sections 5 + 7.

#### 2.3 Planning Tools

**Package:** `ai.kukuvaia.tools`

| Tool | Purpose |
|------|---------|
| `create_plan` | Create step-by-step plan before complex work |
| `complete_step` | Mark step as done, report result |
| `revise_plan` | Update plan when circumstances change |

**Database schema:**

```sql
-- Migration: V3__create_agent_plans.sql
CREATE TABLE kukuvaia_agent.agent_plans (
    session_id  VARCHAR(255) PRIMARY KEY,
    task        TEXT NOT NULL,
    steps       JSONB NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);
```

**Plan-as-tool pattern** (no custom advisor needed):
- System prompt instructs: "For 3+ step tasks, call create_plan FIRST"
- ToolCallAdvisor handles multi-round naturally
- `maxToolRounds` increased from 10 → 20 for plan+verify loops

Full implementation: see analysis section 4.

#### 2.4 Self-Verification Tools

**Package:** `ai.kukuvaia.tools`

| Tool | Purpose |
|------|---------|
| `verify_document` | Run quality checks (structure, links, consistency, formatting) |
| `compare_documents` | Diff two document versions |
| `verify_against_requirements` | LLM-assisted check against requirements list |

**System prompt mandate:**
```
After creating or modifying ANY document:
1. Re-read using read_document
2. Call verify_document with checks: [structure, formatting, consistency]
3. If issues found: fix, then verify again
4. Only report completion after verification passes

NEVER say "done" without verifying.
```

**Verification rules (from Claude Code, non-negotiable):**
```
Report outcomes faithfully: if checks fail, say so with specific issues.
Never claim "all checks pass" when output shows failures.
Never characterize incomplete work as done.
When a check did pass, state it plainly — do not hedge.
```

Full implementation: see analysis section 6.

#### 2.5 System Prompt Architecture

**Package:** `ai.kukuvaia.agent`

| File | What It Does |
|------|-------------|
| `SystemPromptBuilder.java` | Assembles system prompt with static/dynamic boundary for cache efficiency |

**Structure:**
```
[STATIC — globally cacheable across all users/sessions]
  Tool schemas (auto-generated from @McpTool)
  Behavioral rules (verification mandate, quality gates)
  Planning instructions
  Security instructions (from SubAgentGuard)

──── __SYSTEM_PROMPT_DYNAMIC_BOUNDARY__ ────

[DYNAMIC — per user/session, recomputed each turn]
  Persona prompt
  User rules (.kukuvaia/rules/*.md)
  Active skill (if /skill activated)
  Persistent memory (PersistentMemoryAdvisor content)
  Working set (documents in context)
  Token budget warning (if approaching limit)
  Environment details (workspace path, session ID)
```

#### 2.6 Structured Output Chunks (Extended)

Extend `OutputBlock` with agent-specific types:

```java
public sealed interface OutputBlock permits
    TextBlock, TableBlock, CodeBlock, ProgressBlock,
    PlanBlock, StepProgressBlock, VerificationBlock,
    SourceBlock, MemoryBlock, MetadataBlock {}
```

CLI renders each type with dedicated Lipgloss component. SSE stream carries typed events.

#### 2.7 Tool Hooks (Pre/Post Events)

**Package:** `ai.kukuvaia.advisors`

| File | What It Does |
|------|-------------|
| `ToolHookDispatcher.java` | Fires PreToolUse/PostToolUse/PostToolUseFailure events |

**Use cases:**
- Audit logging (every tool call with input/output/duration)
- Rate limiting (prevent agent from calling same tool 50x in a loop)
- Cost tracking (track which tools consume most tokens via result size)
- Auto-memory triggers (detect patterns in tool results)

**Integration with existing security:**
- Runs in advisor chain alongside `ToolResultSanitizingAdvisor`
- PreToolUse can block tool execution (equivalent to permission system)
- PostToolUse feeds into `ProviderAuditLog` for cost tracking

#### Phase 2 Checklist

- [x] `DocumentTools` — 5 file operation tools with sandbox (path traversal, extension whitelist, .bak backup)
- [ ] `FileStateCache` — LRU read deduplication (deferred — optimize later)
- [ ] Tool result persistence — large results → disk (deferred)
- [x] Workspace configuration (`kukuvaia.workspace.root`)
- [x] `MemoryTools` — 4 explicit memory tools (save, search, list, delete)
- [x] `MemoryRepository` — CRUD + full-text search (parameterized queries)
- [x] `PersistentMemoryAdvisor` — auto-inject user+feedback memories at conversation start
- [ ] `MemoryExtractionAdvisor` — async post-response extraction (deferred — Phase 2.5+)
- [x] V2 migration: `agent_memory` table
- [x] `PlanningTools` — 3 planning tools (create_plan, complete_step, revise_plan)
- [x] V3 migration: `agent_plans` table
- [x] `VerificationTools` — 2 verification tools (verify_document, compare_documents)
- [x] `SystemPromptBuilder` — static/dynamic boundary with verification mandate and planning instructions
- [x] Verification mandate in system prompt
- [x] Extended `OutputBlock` types (PlanBlock, VerificationBlock, MetadataBlock)
- [x] `ToolHookDispatcher` — pre/post tool events advisor
- [x] Increase `maxToolRounds` to 20 (set in application.yaml)
- [ ] Integration test: create plan → execute steps → verify → report (deferred — requires running PG + LLM)

---

### Phase 3: Intelligence Layer

**Goal:** Smarter context management, cost optimization, structured workflows, permission control.

#### 3.1 Layered Compaction

**Package:** `ai.kukuvaia.advisors`

Replace `MessageWindowChatMemory` with `LayeredCompactionMemory`:

| Level | Trigger | Strategy | Cost |
|-------|---------|----------|------|
| Microcompact | Every turn | Trim formatting from cached messages | Zero |
| Snip compact | 60% context | Replace middle messages with boundary marker | Zero |
| Auto-compact | 80% context | LLM summarizes snipped messages | Low (Haiku) |
| Context collapse | 95% context | Keep last 5 messages + summary only | Low |

**Key insight:** Snip compact (free) runs before summarization (LLM cost). Most sessions never reach auto-compact.

#### 3.2 Intent-Driven Retrieval

**Package:** `ai.kukuvaia.advisors`

`IntentDetectionAdvisor` classifies messages before the main agent:

| Intent | Tools Loaded | Model | Cost |
|--------|-------------|-------|------|
| `CONVERSATION` | None | Lightweight | Lowest |
| `DOCUMENT_READ` | read, search, find | Standard | Low |
| `DOCUMENT_WRITE` | read, write, edit, verify | Standard | Medium |
| `ANALYSIS` | All | Full | High |

Cost: ~100 input + 30 output tokens per classification (Haiku). Pays for itself by avoiding unnecessary full-model calls.

#### 3.3 Structured Workflows (Tier 2)

**Package:** `ai.kukuvaia.workflows`

Three-tier routing:

```
Tier 1: /command → CommandRouter → deterministic (no LLM)
Tier 2: /workflow → WorkflowRegistry → fixed pipeline (1 LLM call)
Tier 3: free text → ChatClient + ToolCallAdvisor (full agent loop)
```

Workflows for predictable document tasks:

| Workflow | Steps | Why Not Agent |
|----------|-------|---------------|
| `/summarize {id}` | read → LLM summarize → format | Single LLM call, agent would over-think |
| `/validate {id}` | read → check rules → report | Deterministic pipeline |
| `/compare {a} {b}` | read both → diff → analyze | Fixed steps, predictable |
| `/export {id} {format}` | read → convert → return | No LLM needed |

Integration: `CommandRouter` tries commands first, then workflows, then agent loop.

#### 3.4 Tool Concurrency

Mark tools as `readOnly` or `destructive`:

| Tool | ReadOnly | Can Parallel |
|------|----------|-------------|
| `read_document` | Yes | Yes |
| `search_documents` | Yes | Yes |
| `find_documents` | Yes | Yes |
| `get_classifications` | Yes | Yes |
| `write_document` | No | No |
| `edit_document` | No | No |
| `save_memory` | No | No |

Execute readOnly tools in parallel via `CompletableFuture.allOf()`. Destructive tools execute sequentially.

#### 3.5 Token Budget Per Task

**Package:** `ai.kukuvaia.advisors`

`TokenBudgetAdvisor` tracks usage per agentic turn. Injects warning when approaching limit:

```
TOKEN BUDGET WARNING: You have used 85% of your token budget.
Wrap up current work. Summarize what's done and what remains.
```

Configurable per persona:

```yaml
# personas/editor.yaml
budget:
  max_output_tokens_per_turn: 8000
  warning_threshold_percent: 80
```

#### 3.6 Permission Modes Per Persona

Map Claude Code permission modes to persona configuration:

```yaml
# personas/reviewer.yaml — read-only, no approval needed
permission_mode: auto
auto_approve_tools: [read_document, search_documents, find_documents, verify_document]

# personas/editor.yaml — needs approval for writes
permission_mode: default
auto_approve_tools: [read_document, search_documents, find_documents]
require_approval_tools: [write_document, edit_document, delete_memory]

# personas/daemon-validator.yaml — unattended background execution
permission_mode: bypass
budget_guard: { daily_tokens: 100000 }
```

**Security compatibility:**
- Permission modes layer ON TOP of `SubAgentGuard.filterTools()` — guard provides the ceiling, permission mode provides the user-facing control
- `DaemonBudgetGuard` (already implemented) enforces bypass mode limits

#### 3.7 Command Execution (Sandboxed)

**Package:** `ai.kukuvaia.tools`

Allowlisted command execution for document workflows:

| Command | Binary | Use Case |
|---------|--------|----------|
| `markdownlint` | `/usr/local/bin/markdownlint` | Markdown quality |
| `vale` | `/usr/local/bin/vale` | Prose linting |
| `pandoc` | `/usr/bin/pandoc` | Format conversion |
| `git` | `/usr/bin/git` | Version control (read-only: blocked subcommands `push`, `rebase`, `config`) |
| `diff` | `/usr/bin/diff` | File comparison |

**Security:**
- `ProcessBuilder` — no shell expansion, no metacharacters
- Absolute binary paths — no PATH manipulation
- Timeout enforcement (max 120s)
- Output size limit (64 KB)
- Minimal environment (stripped HOME, minimal PATH)
- Blocked git subcommands for safety

**Recommendation:** Start with pure Java tools (diff-utils, flexmark-java) for common ops. Add ProcessBuilder only for tools without good Java equivalents.

#### 3.8 User Extensibility

**Package:** `ai.kukuvaia.extensions`

| File | What It Does |
|------|-------------|
| `ExtensionLoader.java` | Discovers `.kukuvaia/` directory (walks up from CWD) |
| `RulesLoader.java` | `.kukuvaia/rules/*.md` → system prompt injection |
| `SkillsLoader.java` | `.kukuvaia/skills/*/SKILL.md` → on-demand activation |
| `UserCommandLoader.java` | `.kukuvaia/commands/*.yaml` → CommandRegistry |

Three command types: `prompt` (LLM), `shell` (subprocess), `skill` (activate skill).
User personas in `.kukuvaia/personas/*.yaml` override built-in ones by name.

#### Phase 3 Checklist

- [ ] `LayeredCompactionMemory` — snip → auto-compact → collapse (deferred — MessageWindowChatMemory covers basic windowing)
- [x] `IntentDetectionAdvisor` — classify (CONVERSATION/DOCUMENT_READ/DOCUMENT_WRITE/ANALYSIS)
- [x] `WorkflowRegistry` — Tier 2 workflow routing interface + registry
- [ ] CommandRouter integration with WorkflowRegistry (3-tier routing) (deferred)
- [ ] Tool concurrency metadata (readOnly flag) (deferred)
- [ ] Parallel tool execution for read-only tools (deferred)
- [x] `TokenBudgetAdvisor` — per-session budget tracking + warning injection at 80%
- [ ] Permission modes per persona (auto/default/bypass) (deferred — requires PersonaSpec extension)
- [ ] `CommandTools` — allowlisted subprocess execution (deferred — security review first)
- [ ] Command allowlist configuration (deferred)
- [x] `ExtensionLoader` — .kukuvaia/ discovery (walks up from CWD)
- [x] `RulesLoader` — rules/*.md → concatenated for system prompt
- [ ] `SkillsLoader` — skills → on-demand activation (deferred)
- [x] `UserCommandLoader` — .kukuvaia/commands/*.yaml → SlashCommand instances
- [ ] Integration test: full workflow (deferred — requires running PG + LLM)

---

## Advisor Chain (Complete)

Order of execution in the ChatClient advisor chain:

```
HIGHEST_PRECEDENCE     ProviderAuditLog            ✅ Existing — logs provider, model, tokens
HIGHEST_PRECEDENCE + 1 ToolResultSanitizingAdvisor  ✅ Existing — anti-injection boundary
HIGHEST_PRECEDENCE + 5 PersistentMemoryAdvisor      ⬜ Phase 2 — inject cross-session memories
HIGHEST_PRECEDENCE + 10 IntentDetectionAdvisor      ⬜ Phase 3 — classify and route
HIGHEST_PRECEDENCE + 15 WorkingSetAdvisor           ⬜ Phase 2 — inject open documents context
HIGHEST_PRECEDENCE + 20 TokenBudgetAdvisor          ⬜ Phase 3 — budget warnings
DEFAULT                 MessageChatMemoryAdvisor    ⬜ Phase 1 — session history (→ Phase 3: LayeredCompactionMemory)
DEFAULT + 1             ToolCallAdvisor             ⬜ Phase 1 — multi-round tool calling (maxIterations: 20)
LOWEST_PRECEDENCE       ToolHookDispatcher          ⬜ Phase 2 — pre/post tool events
LOWEST_PRECEDENCE       MemoryExtractionAdvisor     ⬜ Phase 2 — async post-response memory extraction
```

**Key:** ✅ = already implemented, ⬜ = to implement.

All new advisors integrate non-destructively with existing security advisors. No changes to `ToolResultSanitizingAdvisor` or `ProviderAuditLog`.

---

## Database Migrations

| Migration | Phase | Tables Created |
|-----------|-------|---------------|
| Spring AI auto | Phase 1 | `kukuvaia_agent.SPRING_AI_CHAT_MEMORY` (auto-created by `initialize-schema: always`) |
| V2 | Phase 2 | `kukuvaia_agent.agent_memory` (persistent cross-session memory) |
| V3 | Phase 2 | `kukuvaia_agent.agent_plans` (plan state per session) |
| Existing | — | `kukuvaia_data.*` (pipeline data, queried by data tools) |

---

## Configuration

```yaml
kukuvaia:
  # Existing
  providers:
    copilot:
      client-id: "Iv1.b507a08c87ecfe98"
      api-url: "https://api.githubcopilot.com"
    smartgate:
      host: ${SMARTGATE_HOST:}
      team: ${SMARTGATE_TEAM:}
      jwt: ${SMARTGATE_JWT:}
  default-provider: copilot
  default-model: claude-sonnet-4.5
  credentials-path: ${user.home}/.kukuvaia/credentials.json

  # Phase 1
  max-tool-rounds: 20                    # increased from 10 for plan+verify loops

  # Phase 2
  workspace:
    root: ${KUKUVAIA_WORKSPACE:/var/kukuvaia/workspaces}
    max-file-size-kb: 5120
    allowed-extensions: [md, yaml, json, xml, csv, txt, html]
    backup-on-write: true
    max-search-results: 100
    max-result-chars: 8000               # persist to disk if exceeded

  memory:
    auto-extraction: true                # enable MemoryExtractionAdvisor
    extraction-model: claude-haiku       # lightweight model for extraction
    max-memories-per-user: 100

  # Phase 3
  commands:
    allowed:
      markdownlint:
        binary: /usr/local/bin/markdownlint
        max-timeout: 60
      vale:
        binary: /usr/local/bin/vale
        max-timeout: 60
      pandoc:
        binary: /usr/bin/pandoc
        max-timeout: 120
      git:
        binary: /usr/bin/git
        max-timeout: 30
        blocked-subcommands: [push, remote, config, rebase]

  compaction:
    snip-threshold-percent: 60
    autocompact-threshold-percent: 80
    collapse-threshold-percent: 95
    summarization-model: claude-haiku

  intent:
    enabled: true
    model: claude-haiku
    cache-ttl-seconds: 0                 # no caching, per-message classification

spring:
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always
  datasource:
    url: ${PG_DSN}
  data:
    mongodb:
      uri: ${MONGODB_URI}
      database: etsl
```

---

## Security Compatibility Matrix

Every new component is verified against existing security controls:

| New Component | ApiAuthFilter | SubAgentGuard | ToolResultSanitizing | PayloadSanitizer | ErrorSanitizer |
|--------------|---------------|---------------|---------------------|-----------------|---------------|
| DocumentTools | Via ChatController | tool_filter applies | Results = data | N/A | Errors sanitized |
| MemoryTools | Via ChatController | tool_filter applies | Results = data | N/A | Errors sanitized |
| PlanningTools | Via ChatController | tool_filter applies | Results = data | N/A | Errors sanitized |
| VerificationTools | Via ChatController | tool_filter applies | Results = data | N/A | Errors sanitized |
| CommandTools | Via ChatController | tool_filter applies | Results = data | N/A | Binary paths validated |
| PersistentMemoryAdvisor | N/A (internal) | N/A | Memory = context | N/A | N/A |
| MemoryExtractionAdvisor | N/A (internal) | N/A | N/A | N/A | N/A |
| IntentDetectionAdvisor | N/A (internal) | N/A | N/A | N/A | N/A |
| ToolHookDispatcher | N/A (internal) | N/A | N/A | N/A | Logs sanitized |
| Workflows | Via CommandController | N/A (fixed pipeline) | N/A (single LLM call) | N/A | Errors sanitized |

**Additional security for new tools:**

| Tool | Threat | Mitigation |
|------|--------|-----------|
| `write_document` | Path traversal | `normalize()` + `startsWith(root)` |
| `write_document` | Symlink escape | `NOFOLLOW_LINKS` |
| `edit_document` | Data loss | Automatic `.bak` backup |
| `search_documents` | Context blowout | maxResultChars → disk persistence |
| `run_command` | Shell injection | ProcessBuilder (no shell), allowlist |
| `run_command` | Resource exhaustion | Timeout + output size limit |
| `save_memory` | Cross-user access | user_id isolation in queries |
| `verify_against_requirements` | LLM cost | Separate lightweight model, max content size |

---

## File Inventory (Target State)

```
src/main/java/ai/kukuvaia/
├── config/                                    Phase 1
│   ├── ChatClientConfig.java                  ChatClient bean + advisor chain
│   ├── MemoryConfig.java                      JdbcChatMemoryRepository + ChatMemory
│   └── ToolRegistryConfig.java                @McpTool resolution + catalog
├── security/                                  ✅ COMPLETE (8 files)
│   ├── ApiAuthFilter.java
│   ├── CredentialsFileGuard.java
│   ├── ErrorSanitizer.java
│   ├── PayloadSanitizer.java
│   ├── SecurityConfig.java
│   ├── ToolResultSanitizingAdvisor.java
│   ├── WebhookAuthFilter.java
│   └── WebhookRateLimiter.java
├── provider/                                  ✅ COMPLETE (2 files)
│   ├── LlmProviderService.java
│   └── ProviderAuditLog.java
├── agent/
│   ├── AgentService.java                      Phase 1 — chat orchestration
│   ├── CommandRouter.java                     Phase 1 — routing (→ Phase 3: 3-tier with workflows)
│   ├── PersonaService.java                    Phase 1 — persona loading
│   ├── SystemPromptBuilder.java               Phase 2 — static/dynamic boundary
│   ├── daemon/                                ✅ COMPLETE (7 files)
│   │   ├── DaemonAgentService.java
│   │   ├── DaemonBudgetGuard.java
│   │   ├── DaemonScheduleGuard.java
│   │   ├── DaemonTaskResult.java
│   │   ├── ExecutionContext.java
│   │   ├── NotificationSanitizer.java
│   │   └── PgNotifyDebouncer.java
│   └── subagent/                              ✅ COMPLETE (4 files, tool registry TODO)
│       ├── SubAgentFactory.java
│       ├── SubAgentGuard.java
│       ├── SubAgentSpec.java
│       └── SubAgentSpecLoader.java
├── tools/                                     Phase 1 + 2
│   ├── PostgresTools.java                     Phase 1 — 5 data tools
│   ├── MongoTools.java                        Phase 1 — 3 data tools
│   ├── AuthorsuiteTools.java                  Phase 1 — 2 data tools
│   ├── DocumentTools.java                     Phase 2 — 5 file tools
│   ├── MemoryTools.java                       Phase 2 — 4 memory tools
│   ├── PlanningTools.java                     Phase 2 — 3 planning tools
│   ├── VerificationTools.java                 Phase 2 — 3 verification tools
│   └── CommandTools.java                      Phase 3 — allowlisted commands
├── advisors/                                  Phase 2 + 3
│   ├── PersistentMemoryAdvisor.java           Phase 2
│   ├── MemoryExtractionAdvisor.java           Phase 2
│   ├── WorkingSetAdvisor.java                 Phase 2
│   ├── ToolHookDispatcher.java                Phase 2
│   ├── IntentDetectionAdvisor.java            Phase 3
│   ├── TokenBudgetAdvisor.java                Phase 3
│   └── LayeredCompactionMemory.java           Phase 3
├── commands/                                  Phase 1
│   ├── HelpCommand.java
│   ├── ValidateCommand.java
│   ├── SearchCommand.java
│   ├── ModelCommand.java
│   └── LoginCommand.java
├── workflows/                                 Phase 3
│   ├── WorkflowRegistry.java
│   ├── SummarizeWorkflow.java
│   ├── ValidateWorkflow.java
│   ├── CompareWorkflow.java
│   └── ExportWorkflow.java
├── extensions/                                Phase 3
│   ├── ExtensionLoader.java
│   ├── RulesLoader.java
│   ├── SkillsLoader.java
│   └── UserCommandLoader.java
├── output/                                    Phase 1 (→ Phase 2: extended)
│   └── OutputBlock.java                       Sealed interface + records
├── api/                                       Phase 1
│   ├── ChatController.java
│   ├── SessionController.java
│   ├── CommandController.java
│   └── webhook/                               ✅ COMPLETE
│       └── WebhookController.java
└── KukuvaiaApplication.java                   Entry point (exists)
```

**Total:** 22 existing + ~35 new = ~57 files
**Phase 1:** ~17 new files (core agent + data tools + API)
**Phase 2:** ~12 new files (document tools + memory + planning + verification + hooks)
**Phase 3:** ~11 new files (compaction + intent + workflows + commands + extensions)

---

## Phase 4: Memory Architecture + Embabel Agents

**Date added:** 2026-04-10
**Prerequisites:** Phases 1-3 (core implemented)
**Architecture docs:** [Memory Architecture](../../docs/architecture/memory-architecture.md), [Embabel Integration](../../docs/architecture/embabel-integration.md)

### Architecture Summary (Phase 4)

```
kukuvaia-engine/ (multi-module, renamed from kukuvaia-server)
├── kukuvaia-memory/          ⬜ NEW — autonomous memory infrastructure
│   ├── model/                    KukuvaiaUser, KukuvaiaSession, ConversationSnapshot, MemoryEntry (extended), Plan
│   ├── repository/               UserRepository, SessionRepository, SmartMemoryRepository, JsonChatMemoryRepository
│   ├── service/                  MemoryExtractionService, MemoryRetrievalService, MemoryConsolidationService
│   ├── advisor/                  SmartMemoryAdvisor (replaces PersistentMemoryAdvisor)
│   └── config/                   MemoryModuleConfig
│
├── kukuvaia-core/            ✅ EXISTS — domain logic (migrates memory classes out)
│   └── (removes: agent/memory/*, config/MemoryConfig — moved to kukuvaia-memory)
│
├── kukuvaia-agents/          ⬜ EXTEND — Embabel production agents
│   ├── agents/PingAgent.kt       ✅ EXISTS (framework test)
│   ├── agents/ResearchAgent.kt   ⬜ NEW (web search + doc analysis)
│   └── agents/ValidationAgent.kt ⬜ NEW (multi-step validation)
│
└── kukuvaia-app/             ✅ EXISTS — Boot entry + V4 migration
```

### Module Dependencies (Phase 4)

```
kukuvaia-memory              ← standalone (Spring JDBC, pgvector, Spring AI ChatMemoryRepository)
kukuvaia-core                ← depends on :kukuvaia-memory
kukuvaia-agents              ← depends on :kukuvaia-core + :kukuvaia-memory + Embabel 0.3.4
kukuvaia-app                 ← depends on all (Boot entry + Flyway)
```

### Phase 4A: Module Creation + DB Schema Consolidation `[Effort: M]`

**Goal:** Create kukuvaia-memory module, consolidate DB schemas, migrate existing memory code.

**DB Schema Decision (2026-04-10):** Consolidate from two schemas (`public` + `kukuvaia_agent`) to one schema (`kukuvaia`). Rationale:
- `public` schema has Spring AI auto-created `SPRING_AI_CHAT_MEMORY` — orphaned from Flyway management
- `kukuvaia_agent` has `agent_memory` + `agent_plans` — managed by Flyway
- One application = one schema. FK constraints require same schema. No "lost tables" in `public`.
- `SPRING_AI_CHAT_MEMORY` eliminated — replaced by `conversations` table with JSONB (via custom `JsonChatMemoryRepository`)
- Schema renamed: `kukuvaia_agent` → `kukuvaia` (shorter, cleaner)

```
⬜ 1. Create kukuvaia-memory/build.gradle
     - java-library plugin
     - Dependencies: spring-boot-starter-jdbc, pgvector, Spring AI (ChatMemoryRepository interface)
⬜ 2. Update settings.gradle: include 'kukuvaia-memory'
⬜ 3. Update kukuvaia-core/build.gradle: add dependency on :kukuvaia-memory
⬜ 4. Update application.yaml:
     - spring.flyway.schemas: kukuvaia (was: kukuvaia_agent)
     - spring.flyway.default-schema: kukuvaia
     - spring.ai.chat.memory.repository.jdbc.initialize-schema: never (was: always)
       → Spring AI no longer auto-creates SPRING_AI_CHAT_MEMORY in public schema
       → Custom JsonChatMemoryRepository replaces it
⬜ 5. V4__consolidate_schema.sql Flyway migration:
     a. Schema consolidation:
        - ALTER SCHEMA kukuvaia_agent RENAME TO kukuvaia
        - DROP TABLE IF EXISTS public.spring_ai_chat_memory (data migrated to conversations)
     b. New tables in kukuvaia schema:
        - CREATE TABLE kukuvaia.users (id, display_name, preferences JSONB, timestamps)
        - CREATE TABLE kukuvaia.sessions (id, user_id FK→users, name, metadata JSONB, status, timestamps)
        - CREATE TABLE kukuvaia.conversations (session_id FK→sessions, messages JSONB, summary, message_count, token_count, timestamps)
     c. Migrate existing tables:
        - ALTER TABLE kukuvaia.agent_memory RENAME TO memories
        - ADD COLUMNS to memories: memory_type, embedding vector(1536), relevance_score, access_count, last_accessed_at, expires_at, session_id FK
        - ALTER TABLE kukuvaia.agent_plans RENAME TO plans
        - ADD COLUMNS to plans: id UUID PK (replace session_id PK), user_id FK, status
     d. Indexes:
        - idx_sessions_user (user_id, updated_at DESC)
        - idx_memories_user_type (user_id, memory_type)
        - idx_memories_relevance (user_id, relevance_score DESC)
        - idx_memories_embedding USING ivfflat (embedding vector_cosine_ops) — conditional on pgvector
        - idx_memories_search GIN (to_tsvector) — replace existing idx_memory_search
⬜ 6. Move from kukuvaia-core to kukuvaia-memory:
     - MemoryEntry.java → memory/model/MemoryEntry.java (extend with new fields)
     - MemoryRepository.java → memory/repository/SmartMemoryRepository.java
     - PersistentMemoryAdvisor.java → memory/advisor/SmartMemoryAdvisor.java (stub, full in 4C)
     - MemoryConfig.java → memory/config/MemoryModuleConfig.java
⬜ 7. Update all SQL in moved classes: schema prefix kukuvaia_agent.* → kukuvaia.*
⬜ 8. Create new model classes:
     - KukuvaiaUser.java (id, displayName, preferences, timestamps)
     - KukuvaiaSession.java (id, userId, name, metadata, status, timestamps)
     - ConversationSnapshot.java (sessionId, messages JSONB, summary, counts)
     - Plan.java (id, sessionId, userId, task, steps, status, timestamps)
⬜ 9. Create UserRepository, SessionRepository (basic CRUD)
⬜ 10. Verify build: ./gradlew clean build (all modules compile)
⬜ 11. Verify Flyway migration: ./gradlew :kukuvaia-app:bootRun (schema migrates cleanly)
```

### Phase 4B: JSONB Conversations + Session Model `[Effort: M]`

**Goal:** Replace row-per-message storage with JSONB. Implement Session-User linkage.

```
⬜ 1. JsonChatMemoryRepository implements ChatMemoryRepository:
     - saveAll(conversationId, messages) → UPSERT conversations.messages JSONB
     - findByConversationId() → SELECT + deserialize JSONB → List<Message>
     - deleteByConversationId() → DELETE
     - findConversationIds() → SELECT session_id
⬜ 2. Message serialization/deserialization (role, content, timestamp, tool_calls)
⬜ 3. Update MemoryModuleConfig:
     - Replace JdbcChatMemoryRepository with JsonChatMemoryRepository
     - Keep MessageWindowChatMemory (20 msg window) on top
⬜ 4. Session creation on first chat:
     - Auto-create User if not exists
     - Auto-create Session with auto-generated name
     - Link conversation_id to session_id
⬜ 5. Session API updates:
     - SessionController: list/rename/archive sessions per user
⬜ 6. Data migration script:
     - SPRING_AI_CHAT_MEMORY rows → conversations JSONB (one-time)
⬜ 7. Tests: JsonChatMemoryRepository integration tests (TestContainers + PG)
```

### Phase 4C: Semantic Search + SmartMemoryAdvisor `[Effort: L]`

**Goal:** pgvector semantic search. Top-K relevant memories instead of load-all.

```
⬜ 1. Enable pgvector extension: CREATE EXTENSION IF NOT EXISTS vector
⬜ 2. Embedding generation:
     - Use Spring AI EmbeddingModel (same provider as chat)
     - Generate embeddings on memory save (async)
⬜ 3. SmartMemoryRepository extensions:
     - semanticSearch(userId, queryEmbedding, limit) → vector cosine similarity
     - hybridSearch(userId, query, limit) → vector + BM25 combined ranking
     - updateAccessStats(memoryId) → access_count++, last_accessed_at = NOW()
⬜ 4. MemoryRetrievalService:
     - Input: user message + userId
     - Embed message → hybrid search → rank by similarity * relevance_score
     - Return top 5-10 memories, formatted as concise bullets
⬜ 5. SmartMemoryAdvisor (full implementation):
     - Replace PersistentMemoryAdvisor
     - Before each LLM call: embed user message → retrieve top-K → inject into system prompt
     - Advisor order: HIGHEST_PRECEDENCE + 5 (same position in chain)
⬜ 6. Tests: semantic search accuracy, advisor injection, relevance ranking
```

### Phase 4D: Extraction Pipeline + Lifecycle `[Effort: L]`

**Goal:** Auto-extract knowledge from conversations. Memory lifecycle management.

```
⬜ 1. MemoryExtractionService:
     - Triggered after session ends (or after N messages)
     - LLM analyzes conversation → extracts:
       - Episodic: "what happened" (distilled summary)
       - Semantic: "new facts" (entities, preferences)
       - Procedural: "what worked" (from user corrections)
     - Generate embeddings per extracted entry
     - Conflict resolution: duplicate check → UPDATE vs INSERT
⬜ 2. MemoryConsolidationService (@Scheduled):
     - Relevance decay: unused >30 days → score *= 0.8
     - Episodic compaction: >90 days → summarize → delete original
     - TTL enforcement: expires_at < NOW() → delete
     - Similar memory merge: cosine_similarity > 0.95 → consolidate
⬜ 3. Conversation summary:
     - After session ends: LLM generates 1-2 sentence summary
     - Stored in conversations.summary column
⬜ 4. Tests: extraction accuracy, consolidation correctness, TTL enforcement
```

### Phase 4E: Embabel Agent Integration `[Effort: M]`

**Goal:** Production Embabel agents using kukuvaia-memory.

```
⬜ 1. ResearchAgent.kt:
     - @Agent with GOAP planning
     - Actions: searchWeb, analyzeDocument, compileReport
     - Injects SmartMemoryRepository for context retrieval
     - Uses multi-model LLM (cheap for draft, powerful for final)
⬜ 2. ValidationAgent.kt:
     - @Agent for multi-step validation workflows
     - Actions: checkStatus, runValidation, analyzeResults, generateReport
     - Saves validation results to memory (episodic)
⬜ 3. Embabel ↔ Memory bridge:
     - Embabel ContextRepository backed by kukuvaia-memory SmartMemoryRepository
     - Embabel blackboard objects can reference memories by ID
⬜ 4. AgentService integration:
     - ChatClient can delegate complex tasks to Embabel agents
     - Results flow back as OutputBlocks
⬜ 5. Tests: agent integration tests with FakeOperationContext
```

### Phase 4 File Inventory

```
kukuvaia-memory/ (NEW MODULE — ~15 files)
├── build.gradle
├── src/main/java/ai/kukuvaia/memory/
│   ├── model/
│   │   ├── KukuvaiaUser.java
│   │   ├── KukuvaiaSession.java
│   │   ├── ConversationSnapshot.java
│   │   ├── MemoryEntry.java               (extended from core)
│   │   └── Plan.java
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── SessionRepository.java
│   │   ├── SmartMemoryRepository.java      (extended from core)
│   │   └── JsonChatMemoryRepository.java   (implements ChatMemoryRepository)
│   ├── service/
│   │   ├── MemoryExtractionService.java
│   │   ├── MemoryRetrievalService.java
│   │   └── MemoryConsolidationService.java
│   ├── advisor/
│   │   └── SmartMemoryAdvisor.java         (replaces PersistentMemoryAdvisor)
│   └── config/
│       └── MemoryModuleConfig.java

kukuvaia-agents/ (EXTEND — ~3 new Kotlin files)
├── agents/ResearchAgent.kt
├── agents/ValidationAgent.kt
└── agents/model/ (domain data classes)

kukuvaia-app/ (1 new migration)
└── db/migration/V4__consolidate_memory.sql

kukuvaia-core/ (REMOVE — ~4 files migrated to memory module)
└── (remove: agent/memory/*, config/MemoryConfig.java)
```

**Phase 4 Total:** ~19 new files + 1 migration + 4 files migrated

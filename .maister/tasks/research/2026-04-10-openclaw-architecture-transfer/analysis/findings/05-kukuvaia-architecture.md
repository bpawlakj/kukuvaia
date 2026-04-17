# Findings: Kukuvaia Current Architecture & Extension Points

## Summary

Kukuvaia is a multi-module Java/Spring AI agent platform with a well-defined architecture split across `kukuvaia-core` (Java domain logic), `kukuvaia-agents` (Kotlin/Embabel GOAP orchestration), `kukuvaia-memory` (pgvector semantic search), and `kukuvaia-app` (Boot entry point). It currently has 9 distinct extension points (YAML+Lua tools, rules, skills, commands, personas, specialists, advisor chain, @Tool methods, Embabel agents) and a robust security layer (8 production-ready classes, full STRIDE review). However, it lacks a formal plugin SDK, channel abstraction, marketplace/registry, trust model document, doctor/diagnostic command, approval system, and build/test gate system.

## Key Findings

### Finding 1: YAML+Lua Tool Pipeline -- Hot-Reload Extension System

**Source**: `kukuvaia-core: extensions/ToolLoader.java, ToolSpec.java, ToolWatcher.java, ScriptToolCallbackProvider.java`
**Evidence**: Users define tools as `TOOL.md` files with YAML frontmatter and pipeline steps. ToolLoader parses, ToolWatcher monitors `.kukuvaia/tools/` with debounced WatchService (500ms), ScriptToolCallbackProvider bridges to Spring AI's ToolCallbackProvider. Hot reload, Lua sandbox, per-tool config.yaml.
**Relevance**: Equivalent of OpenClaw's plugin entry points but without manifest schema, capability model, or lifecycle management.

### Finding 2: Structured Rules System -- Scoped LLM Behavior Control

**Source**: `kukuvaia-core: extensions/RulesLoader.java, RuleSpec.java`
**Evidence**: Three types (CONSTRAINT, BEHAVIOR, FORMAT), three scopes (GLOBAL, PERSONA, INTENT), priority ordering, conditional application, system prompt injection.
**Relevance**: Analogous to OpenClaw's hooks but simpler -- rules are prompt-injected, not runtime hooks. Advisor chain is the direct runtime equivalent.

### Finding 3: Spring AI Advisor Chain -- The Primary Hook System

**Source**: `kukuvaia-core: config/ChatClientConfig.java`, implementation plan
**Evidence**: Ordered advisor chain: ProviderAuditLog → ToolResultSanitizingAdvisor → SmartMemoryAdvisor → MessageChatMemoryAdvisor → ToolCallAdvisor → ToolHookDispatcher. Plus IntentDetectionAdvisor, TokenBudgetAdvisor.
**Relevance**: Direct equivalent of OpenClaw's hooks. Lacks plugin-contributed hook model -- all advisors currently built-in.

### Finding 4: User Extensibility -- The .kukuvaia/ Directory System

**Source**: `kukuvaia-core: extensions/ExtensionLoader.java, UserCommandLoader.java, SkillsLoader.java`
**Evidence**: 6 file-based extension points: tools (.kukuvaia/tools/), rules (.kukuvaia/rules/), skills (.kukuvaia/skills/), commands (.kukuvaia/commands/), personas (.kukuvaia/personas/), specialists (.kukuvaia/specialists/).
**Relevance**: Gaps vs OpenClaw: no manifest/capability model, no dependency tracking, no install/update lifecycle, no marketplace, no memory plugin slot.

### Finding 5: Sub-Agent System -- Isolated Delegation with Security Guards

**Source**: `kukuvaia-core: agent/subagent/` (4 classes)
**Evidence**: Isolated ChatClient per sub-agent, maxDepth=1, SubAgentGuard.filterTools(), delegation tools removed, prompt hardening with anti-injection directives, resource limits, semaphore concurrency.
**Relevance**: OpenClaw's multi-agent safety rules go beyond -- workspace isolation, git safety, approval system.

### Finding 6: Security Layer -- Defense in Depth, STRIDE Reviewed

**Source**: `docs/architecture/security-review.md`, `.maister/docs/standards/security/`
**Evidence**: 8 production classes: ApiAuthFilter, WebhookAuthFilter, RateLimitFilter, PayloadSanitizer, ErrorSanitizer, ToolResultSanitizingAdvisor, CredentialsFileGuard, SecurityConfig. Full STRIDE analysis. 22 findings, 17 implemented, 5 TODO.
**Relevance**: Solid foundations. Lacks: formal trust model doc, container sandbox, approval workflow, security audit command, tool policy levels.

### Finding 7: Daemon System -- Autonomous Background Execution

**Source**: `kukuvaia-core: agent/daemon/` (7 classes)
**Evidence**: DaemonAgentService, DaemonBudgetGuard (daily token budget), DaemonScheduleGuard (skip-if-running), PgNotifyDebouncer, NotificationSanitizer, DaemonTaskResult, ExecutionContext. Triggers: @Scheduled, webhook POST, PG LISTEN/NOTIFY.
**Relevance**: Analogous to OpenClaw's automation but lacks standing orders, taskflow, poll triggers.

### Finding 8: Provider Routing -- Multi-Provider with Context-Aware Switching

**Source**: `docs/architecture/auth-and-providers.md`, LlmProviderService
**Evidence**: Copilot (OAuth), SmartGate (JWT), GitHub Models (planned). Context-aware: INTERACTIVE→user choice, DAEMON→SmartGate. No provider plugin interface, no per-channel overrides.
**Relevance**: No provider plugin interface -- centrally managed. No channel concept.

### Finding 9: Memory Architecture -- Three-Type System with pgvector

**Source**: `docs/architecture/memory-architecture.md`, kukuvaia-memory module
**Evidence**: Separate Gradle module. SmartMemoryRepository (pgvector + FTS), SmartMemoryAdvisor. Three types planned: episodic, semantic, procedural. Deferred: extraction pipeline, consolidation.
**Relevance**: Built-in infrastructure, no plugin interface. OpenClaw has pluggable memory slot.

### Finding 10: Embabel GOAP Agent Orchestration

**Source**: `docs/architecture/embabel-integration.md`, PingAgent.kt
**Evidence**: Embabel 0.3.4 with @Agent/@Action/@AchievesGoal, A* planning, blackboard pattern, MCP Server export. Only PingAgent exists.
**Relevance**: Advantage over OpenClaw's simpler agent model. Not a gap.

### Finding 11: Output Protocol and Design System

**Source**: `kukuvaia-core: output/OutputBlock.java`, `docs/architecture/design-system.md`
**Evidence**: Sealed OutputBlock with 7 types. Shared theme via kukuvaia-theme.yaml → Lipgloss (CLI) + CSS (web). SSE delivery.
**Relevance**: No channel abstraction -- always HTTP/SSE. OpenClaw routes to Discord, Slack, etc.

### Finding 12: Three-Tier Command Routing

**Source**: CommandRouter, CommandRegistry, WorkflowRegistry
**Evidence**: Tier 1 (slash, deterministic), Tier 2 (workflows, 1 LLM call), Tier 3 (free text, full loop). 5 built-in commands.
**Relevance**: Clean pattern. Minimal command set vs OpenClaw's richer built-in commands.

## Architecture Gaps Identified

| # | Gap | OpenClaw Has | Kukuvaia Status |
|---|-----|-------------|-----------------|
| 1 | Plugin SDK / Formal Extension Contract | Unified SDK, manifest, capabilities | 6 separate file loaders, no unified contract |
| 2 | Channel Abstraction | Transport→adapter→plugin, 10+ channels | HTTP/SSE only |
| 3 | Marketplace / Registry | ClawHub: discover, install, update | No discovery/install |
| 4 | Formal Trust Model | SECURITY.md with operator trust | STRIDE review but no trust model doc |
| 5 | Sandbox Backends | Docker/SSH/OpenShell | Lua sandbox only |
| 6 | Doctor / Diagnostic Command | 19+ check categories + repairs | Health endpoints, no agent diagnostics |
| 7 | Approval System | Tool call approval before execution | ToolHookDispatcher logs only |
| 8 | Build/Test Gate System | Dev/landing/CI gate | No formal verification levels |
| 9 | Prompt Cache Stability | Explicit cache boundary marker | Has static/dynamic boundary but not optimized |
| 10 | Provider Plugin Interface | 109 extensions | Hardcoded Copilot/SmartGate |

## Existing Extension Points (Must Be Preserved)

| Extension Point | Mechanism | Location |
|----------------|-----------|----------|
| Tools (YAML+Lua) | ToolLoader + ToolWatcher + ScriptToolCallbackProvider | `.kukuvaia/tools/` |
| Rules | RulesLoader + RuleSpec | `.kukuvaia/rules/` |
| Skills | SkillsLoader + SkillExecutor + SkillRegistry | `.kukuvaia/skills/` |
| Commands | UserCommandLoader | `.kukuvaia/commands/` |
| Personas | PersonaService + PersonaSpec | `.kukuvaia/personas/` |
| Specialists | SubAgentSpecLoader + SubAgentSpec | `.kukuvaia/specialists/` |
| Advisor chain | ChatClientConfig + BaseAdvisor | Spring beans |
| @Tool methods | ToolCallbackProvider auto-discovery | Spring components |
| Embabel agents | @Agent/@Action/@AchievesGoal | kukuvaia-agents module |

## Source Citations

| File | Notes |
|------|-------|
| `.maister/docs/project/architecture.md` | Multi-module structure, data flow |
| `.maister/docs/project/implementation-plan.md` | Three phases, advisor chain order |
| `.maister/docs/project/vision.md` | Tools as YAML pitch, goals |
| `.maister/docs/project/roadmap.md` | 3 milestones, marketplace planned |
| `.maister/docs/project/tech-stack.md` | Java 21, Spring Boot 3.4.4, Spring AI 1.1.0 |
| `docs/architecture/auth-and-providers.md` | Provider routing |
| `docs/architecture/memory-architecture.md` | Three memory types, pgvector |
| `docs/architecture/embabel-integration.md` | GOAP planning |
| `docs/architecture/security-review.md` | Full STRIDE analysis |
| `docs/architecture/tool-filtering.md` | Name-based to tag-based evolution |
| `docs/architecture/health-resilience.md` | Three-mode operation |
| `.maister/docs/standards/security/` (5 files) | Security standards |
| `.maister/docs/standards/backend/architecture.md` | Base package, advisor pattern |
| `CLAUDE.md` | Architecture overview |
| Source code (65+ Java files, 1 Kotlin file) | Extension loaders, security classes, agent system |

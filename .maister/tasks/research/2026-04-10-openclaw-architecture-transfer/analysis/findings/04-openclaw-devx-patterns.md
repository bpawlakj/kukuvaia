# Findings: OpenClaw Developer Experience Patterns

## Summary

OpenClaw implements five distinct developer experience patterns: (1) ACP bridge translating between IDE protocols and Gateway sessions via stdio, (2) comprehensive `doctor` diagnostic command with modular health checks and repair steps, (3) multi-agent safety rules for concurrent git operations, (4) three-tier build/test gate terminology with fast-commit escape hatches, and (5) prompt cache stability techniques using a cache boundary marker to separate stable prompt prefixes from dynamic suffixes.

## Key Findings

### Finding 1: ACP Bridge -- Protocol Translation Layer

**Source**: `docs.acp.md:1-240`, `src/acp/server.ts`, `src/acp/client.ts`, `src/acp/translator.ts`, `src/acp/session.ts`, `src/acp/event-mapper.ts`, `src/acp/approval-classifier.ts`
**Evidence**: Gateway-backed protocol translator speaking ACP over stdio for IDE integration. Session mapping (ACP→Gateway via `acp:<uuid>` keys), bidirectional event translation (ACP prompt→Gateway chat.send, streaming events→ACP messages), approval classifier (tool calls classified into safety tiers: readonly_scoped, readonly_search, mutating, exec_capable, control_plane), DoS protection (2MB prompt limit, rate limiting). In-memory session store with idle TTL eviction (24h), max 5000 sessions.
**Relevance**: Transferable as a separate transport adapter. Spring AI ChatClient exposed via ACP-compatible endpoint. Session mapping maps to kukuvaia's JdbcChatMemoryRepository. Approval classifier maps to tool filtering per persona.

### Finding 2: Doctor Command -- Modular Diagnostic System

**Source**: `docs/gateway/doctor.md:1-543`, `src/flows/doctor-health.ts`, `src/flows/doctor-health-contributions.ts`
**Evidence**: 19+ diagnostic categories as `DoctorHealthContribution` objects with id, kind, surface, and async run function. Repair mode hierarchy: read-only → --yes (accept defaults) → --repair (apply fixes) → --repair --force (aggressive). Categories: config normalization, state integrity, gateway health, auth health, security warnings, sandbox repair, session lock cleanup, memory readiness, shell completion, workspace status, bootstrap size, platform notes, plugin manifests, cron migration, gateway services, browser migration. Plugin doctor contracts allow extensions to contribute checks.
**Relevance**: `/doctor` slash command using Spring Boot Actuator health indicators. Modular contribution = `HealthIndicator` interface. Repair mode = admin endpoints. Categories: DB connectivity, LLM provider health, MCP tool connectivity, memory readiness, session integrity, security audit.

### Finding 3: Multi-Agent Safety Rules

**Source**: `AGENTS.md:305-316`
**Evidence**: Convention-based rules: no git stash/worktree/branch switch without permission, scoped commits (your changes only), grouped commit/pull/push cycles, session isolation (one session per agent), ignore unrecognized files, auto-resolve formatting-only diffs, focus reports on own edits.
**Relevance**: Agent instructions, not code guards. Translate to system prompt directives for sub-agents/daemons with git tool access. Kukuvaia's sub-agent system (max depth=1) already enforces session isolation.

### Finding 4: Build/Test Gate Terminology

**Source**: `AGENTS.md:140-163`
**Evidence**: Three-tier framework: **Local dev gate** (fast loop: `pnpm check` + scoped test), **Landing gate** (pre-push: check + test + build when touching boundaries), **CI gate** (workflow-specific). Plus: CI architecture gate (boundary policy guards), Hard gate (build MUST pass when touching module boundaries), Fast-commit mode (`FAST_COMMIT=1`), Scoped vs full-suite testing.
**Relevance**: For kukuvaia: local dev gate = `./gradlew spotlessCheck test --tests "Changed*"`, landing gate = `./gradlew check test`, CI gate = GitHub Actions, hard gate = `./gradlew build` when touching module boundaries. Shared vocabulary across engine (Java/Kotlin) and CLI (Go).

### Finding 5: Prompt Cache Stability

**Source**: `AGENTS.md:166-172`, `src/agents/prompt-cache-stability.ts`, `src/agents/system-prompt-cache-boundary.ts`, `src/agents/system-prompt.ts:700-755`
**Evidence**: Cache boundary marker `<!-- OPENCLAW_CACHE_BOUNDARY -->` divides system prompt into stable prefix (persona, tool definitions) and dynamic suffix (session context). `normalizePromptCapabilityIds()` sorts capabilities alphabetically. Provider contributions have separate `stablePrefix` and `dynamicSuffix` fields. Text normalization for byte-identical output. Rules: don't rewrite old history bytes, prefer tail mutation for compaction.
**Relevance**: Directly applicable to kukuvaia's AgentService system prompt construction. Separate static persona/tool instructions from dynamic session context. Spring AI SystemPromptTemplate with stable-prefix/dynamic-suffix split. Deterministic ordering for MCP tool registration.

### Finding 6: ACP Approval Classifier -- Tool Safety Classification

**Source**: `src/acp/approval-classifier.ts:1-228`
**Evidence**: Tool calls classified into `AcpApprovalClass`: readonly_scoped (auto-approved, CWD-scoped), readonly_search (auto-approved), mutating (requires approval), exec_capable (requires approval), control_plane (requires approval), interactive (requires approval), other/unknown (requires approval). CWD scoping resolves ~, file://, relative paths. Multi-source name extraction with cross-validation.
**Relevance**: Maps to kukuvaia's tool filtering. Implementable as Spring AI advisor intercepting tool calls.

### Finding 7: Doctor Plugin Contract Registry

**Source**: `src/plugins/doctor-contract-registry.ts:1-60`
**Evidence**: Plugins contribute doctor checks via `PluginDoctorContractEntry` with pluginId, rules, and normalizeCompatibilityConfig. JIT loading and caching per workspace.
**Relevance**: Extensions contribute health checks via Spring `HealthContributor` SPI.

## Patterns Identified

1. **Protocol Bridge**: Translate between external protocol (ACP/stdio) and internal (Gateway/WS). For IDE integration.
2. **Modular Diagnostic**: Pluggable contributions, escalating repair modes, config migration. Maps to Spring Boot Actuator.
3. **Convention-Based Safety**: Multi-agent safety via instructions, not code guards. System prompt directives.
4. **Gate Terminology**: Three-tier verification vocabulary. Any CI/CD documentation.
5. **Prompt Cache Stability**: Structural static/dynamic separation with boundary marker. Spring AI prompt construction.
6. **Tool Safety Classification**: Hierarchical approval classes with CWD-scoped auto-approval. Tool filtering advisor.
7. **Extensible Doctor Contract**: Plugins contribute diagnostics. Spring HealthContributor SPI.

## Source Citations

| File | Notes |
|------|-------|
| `docs.acp.md` | ACP bridge documentation |
| `src/acp/server.ts` | ACP server entry point |
| `src/acp/translator.ts` | Core protocol translation (44KB) |
| `src/acp/session.ts` | In-memory session store |
| `src/acp/approval-classifier.ts` | Tool approval classification |
| `src/acp/policy.ts` | Policy: enable/disable, dispatch control |
| `docs/gateway/doctor.md` | Full doctor reference: 19 categories |
| `src/flows/doctor-health.ts` | Doctor orchestration flow |
| `src/flows/doctor-health-contributions.ts` | 25+ diagnostic modules |
| `src/commands/doctor-repair-mode.ts` | Repair mode resolution |
| `src/plugins/doctor-contract-registry.ts` | Plugin doctor contracts |
| `AGENTS.md:305-316` | Multi-agent safety rules |
| `AGENTS.md:140-163` | Gate terminology |
| `AGENTS.md:166-172` | Prompt cache stability rules |
| `src/agents/prompt-cache-stability.ts` | Capability ID normalization |
| `src/agents/system-prompt-cache-boundary.ts` | Cache boundary marker |
| `src/agents/system-prompt.ts` | Prompt assembly |

# Research Report: OpenClaw Architecture Transfer to Kukuvaia

**Research Type**: Mixed (technical codebase analysis + requirements + best practices)
**Date**: 2026-04-10
**Methodology**: Multi-source comparative architecture analysis across 5 parallel information-gathering agents

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Research Objectives](#research-objectives)
3. [Methodology](#methodology)
4. [Transferable Architectural Elements](#transferable-architectural-elements)
5. [Detailed Transfer Analysis](#detailed-transfer-analysis)
6. [Architecture Compatibility Assessment](#architecture-compatibility-assessment)
7. [Implementation Order](#implementation-order)
8. [Risks and Mitigations](#risks-and-mitigations)
9. [Conclusion](#conclusion)
10. [Appendices](#appendices)

---

## Executive Summary

This report analyzes which architectural elements from the OpenClaw agent platform are transferable to kukuvaia and how they should be adapted to the Java/Spring AI stack. Five parallel agents examined OpenClaw's plugin architecture, security model, channel/skills system, developer experience patterns, and kukuvaia's current architecture.

**Key findings**: OpenClaw's most transferable patterns are not its large-scale abstractions (plugin SDK, channel system, marketplace) but its security primitives and extensibility contracts. Kukuvaia already has structural equivalents for most OpenClaw patterns -- Spring AI advisors map to hooks, Spring DI maps to the plugin registry, `.kukuvaia/` maps to workspace extensions. The gaps are in granularity and safety: kukuvaia lacks tool policy groups, external content wrapping, prompt cache stability, and diagnostic infrastructure.

**Top recommendations** (in priority order):
1. **Tool policy groups with deny-always-wins** -- Spring `@ConfigurationProperties` + `ToolFilteringAdvisor` enhancement
2. **External content wrapping with boundary markers** -- `ExternalContentWrappingAdvisor` extending existing `ToolResultSanitizingAdvisor`
3. **Dangerous config flag naming convention** -- `dangerously*` prefix on any security-weakening property
4. **Prompt cache stability** -- boundary marker + deterministic tool ordering in `AgentService`
5. **Skills enrichment** -- multi-source precedence + requirement declarations in `SkillsLoader`
6. **Doctor command** -- `/doctor` slash command aggregating Spring Boot `HealthIndicator` beans

**Confidence level**: HIGH for items 1-6 (clear Spring mappings, builds on existing infrastructure). MEDIUM for unified plugin SDK (high value but premature). LOW for channel abstraction and marketplace (solve problems kukuvaia does not yet have).

---

## Research Objectives

### Primary Research Question
Which architectural elements from OpenClaw are transferable to kukuvaia, and how should they be adapted to the Java/Spring AI stack?

### Sub-Questions
1. What patterns does OpenClaw use for extensibility, and which have Spring equivalents?
2. What security mechanisms does OpenClaw implement beyond kukuvaia's current layer?
3. How does OpenClaw handle multi-channel communication, and is this relevant for kukuvaia?
4. What developer experience patterns improve agent platform quality?
5. Where are kukuvaia's architectural gaps relative to OpenClaw's maturity?

### Scope
- **Included**: Architecture patterns, security models, extensibility contracts, developer experience, implementation feasibility in Java/Spring AI
- **Excluded**: OpenClaw's specific UI/UX, TypeScript implementation details, business logic, pricing/licensing, community management

---

## Methodology

### Research Approach
Five specialized agents conducted parallel analysis:
1. **Plugin Architecture Agent** -- analyzed OpenClaw's plugin SDK, manifest system, capability model, hooks, and slot boundaries
2. **Security/Trust Agent** -- analyzed trust boundaries, sandbox backends, approval systems, content wrapping, audit infrastructure
3. **Channels/Skills Agent** -- analyzed channel abstraction, routing, skills system, memory slot, automation hooks
4. **Developer Experience Agent** -- analyzed ACP bridge, doctor command, multi-agent safety, build gates, prompt cache
5. **Kukuvaia Architecture Agent** -- mapped existing extension points, security layer, identified gaps

### Data Sources
- OpenClaw documentation: ~15 architecture/security docs
- OpenClaw source code: ~25 TypeScript source files across plugin-sdk, security, channels, agents, ACP
- Kukuvaia documentation: ~12 architecture/standards docs
- Kukuvaia source code: 65+ Java files, 1 Kotlin file
- Kukuvaia standards: 15 standards documents across 4 domains

### Analysis Framework
Mixed framework combining:
- **Technical Analysis**: component mapping, pattern identification, flow analysis
- **Requirements Analysis**: gap identification, constraint assessment, priority evaluation
- **Best Practices Comparison**: industry standard mapping, trade-off analysis

---

## Transferable Architectural Elements

### Tier 1: High Value, Direct Transfer (Do First)

| Element | OpenClaw Pattern | Spring Mapping | Effort | Impact |
|---------|-----------------|----------------|--------|--------|
| Tool policy groups | Allow/deny groups, deny-always-wins | `@ConfigurationProperties` + `ToolFilteringAdvisor` | Small | High |
| External content wrapping | Boundary markers, homoglyph folding | `ExternalContentWrappingAdvisor` | Small | High |
| Dangerous config naming | `dangerously*` prefix convention | Naming convention on `kukuvaia.*` properties | Minimal | Medium |
| Prompt cache stability | Cache boundary marker, deterministic ordering | `AgentService` prompt construction | Small | Medium |

### Tier 2: High Value, Requires Adaptation

| Element | OpenClaw Pattern | Spring Mapping | Effort | Impact |
|---------|-----------------|----------------|--------|--------|
| Skills enrichment | Multi-source precedence, requirements, eligibility | `SkillsLoader` + `SkillSpec` enhancement | Medium | High |
| Doctor command | 19+ modular diagnostics with repair modes | `/doctor` command + `HealthIndicator` beans | Medium | Medium |
| Tool safety classification | 6-tier approval classes | `ToolSafetyClassifier` service | Medium | Medium |
| Trust model documentation | SECURITY.md with explicit boundaries | Documentation | Small | Medium |
| Advisor chain enrichment | Terminal semantics, fail-open/closed | Custom advisor chain configuration | Medium | Medium |
| Session key grammar | Channel-aware structured session IDs | `SessionKeyBuilder` utility | Small | Low |

### Tier 3: Future Value, Deferred

| Element | Why Deferred | Prerequisite |
|---------|-------------|-------------|
| Unified extension metadata schema | Needs proven pattern first | Skills enrichment validates approach |
| Plugin SDK / kukuvaia-plugin-api | Large effort, limited current user base | Metadata schema + stable extension types |
| Exclusive slot system | Only memory needs it currently | Plugin SDK |
| Exec approval system | No shell/file tools yet | Tool safety classification |
| Sandbox backends (Docker) | No shell/file tools yet | Document agent phase |
| Build/test gate terminology | Process pattern, not code | CI/CD pipeline setup |

### Not Transferable

| Element | Reason |
|---------|--------|
| Channel abstraction (~25 adapter surfaces) | Kukuvaia has one transport (HTTP/SSE). CLI is a thin client. Over-engineering for current state. |
| ClawHub marketplace | Requires plugin SDK + user community. No distribution need yet. |
| ACP/IDE bridge | IDE integration not on kukuvaia roadmap. Different user interaction model. |
| DM pairing / allowlist security | Addresses messaging platform trust, not applicable to HTTP/SSE. |
| Formal verification (TLA+) | Aspirational. Integration tests are the pragmatic equivalent. |
| Multi-channel routing cascade | No channels to route between. Single-agent default routing suffices. |
| SSRF protection | No web fetch tools yet. Implement when tools added. |

---

## Detailed Transfer Analysis

### 1. Tool Policy Groups

- **OpenClaw Pattern**: Tools organized into groups (runtime, fs, sessions, web, ui, automation). Per-agent policies with `allow` and `deny` lists. Groups reference with `group:fs` syntax. Critical rule: deny always wins over allow.
- **Kukuvaia Gap**: Tool filtering is currently name-based per persona (tag-based evolution planned). No deny lists, no group concept, no deny-always-wins rule.
- **Spring Adaptation**: Define tool groups in `application.yml` under `kukuvaia.tools.groups`. Implement `ToolPolicyService` with `@ConfigurationProperties(prefix = "kukuvaia.tools")`. Enhance existing `ToolFilteringAdvisor` to resolve groups, apply allow/deny with deny-always-wins. Per-persona policies in persona YAML (`tools: { allow: ["group:fs"], deny: ["shell_exec"] }`).
- **Effort**: Small -- extends existing ToolFilteringAdvisor, adds configuration class
- **Dependencies**: None. Builds on existing infrastructure.
- **Priority**: P0

```yaml
# Example configuration
kukuvaia:
  tools:
    groups:
      fs: [read_file, write_file, list_files]
      web: [http_get, http_post]
      dangerous: [shell_exec, process_run]
    default-policy:
      deny: [group:dangerous]
```

### 2. External Content Wrapping

- **OpenClaw Pattern**: Tool results and external content wrapped with random boundary markers (crypto-random IDs). Unicode homoglyph folding normalizes 30+ bracket lookalike characters. Invisible character stripping. Source labeling on all external content.
- **Kukuvaia Gap**: `ToolResultSanitizingAdvisor` exists but applies simple boundary markers without crypto-random IDs, homoglyph folding, or source labeling.
- **Spring Adaptation**: Create `ExternalContentWrappingAdvisor` (or enhance existing `ToolResultSanitizingAdvisor`) that: (a) generates UUID-based boundary markers per tool result, (b) applies `java.text.Normalizer.normalize(input, Form.NFKC)` for homoglyph folding, (c) strips zero-width characters via regex, (d) labels content with source tool name. Insert in advisor chain after tool execution, before LLM sees results.
- **Effort**: Small -- single advisor class, ~100 lines
- **Dependencies**: None.
- **Priority**: P0

```java
// Sketch of key method
public String wrapExternalContent(String content, String source) {
    String boundaryId = UUID.randomUUID().toString().substring(0, 16);
    String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC);
    String cleaned = ZERO_WIDTH_PATTERN.matcher(normalized).replaceAll("");
    return "<tool-result source=\"%s\" boundary=\"%s\">\n%s\n</tool-result boundary=\"%s\">"
        .formatted(source, boundaryId, cleaned, boundaryId);
}
```

### 3. Dangerous Config Flag Naming

- **OpenClaw Pattern**: All configuration properties that weaken security use `dangerous*` or `dangerously*` prefixes. Security audit warns when any dangerous flag is enabled.
- **Kukuvaia Gap**: No naming convention for security-weakening configuration.
- **Spring Adaptation**: Naming convention for `kukuvaia.*` properties. Any property that disables authentication, allows raw shell access, skips sanitization, etc., must use `dangerously-` prefix. Add `@PostConstruct` log warning in `SecurityConfig` when any `dangerously-*` property is enabled.
- **Effort**: Minimal -- naming convention + warning log
- **Dependencies**: None.
- **Priority**: P0

```yaml
# Example
kukuvaia:
  security:
    dangerously-disable-auth: false          # Logged warning if true
    dangerously-allow-raw-shell: false       # Logged warning if true
    dangerously-skip-content-wrapping: false  # Logged warning if true
```

### 4. Prompt Cache Stability

- **OpenClaw Pattern**: System prompt divided by `<!-- CACHE_BOUNDARY -->` marker into stable prefix (persona definition, tool descriptions, rules) and dynamic suffix (session context, memory, recent messages). Tool capabilities sorted alphabetically for byte-identical output. Text normalization for whitespace consistency.
- **Kukuvaia Gap**: System prompt construction has static/dynamic conceptual separation but no explicit boundary marker, no deterministic tool ordering, no cache optimization.
- **Spring Adaptation**: In `AgentService` prompt construction: (a) sort tool descriptions alphabetically before injection, (b) insert explicit `<!-- KUKUVAIA_CACHE_BOUNDARY -->` marker between static and dynamic sections, (c) normalize whitespace in static section. Spring AI `SystemPromptTemplate` with two-part template: `{stablePrefix}\n<!-- KUKUVAIA_CACHE_BOUNDARY -->\n{dynamicSuffix}`.
- **Effort**: Small -- modification to existing prompt assembly in AgentService
- **Dependencies**: None.
- **Priority**: P1

### 5. Skills Enrichment

- **OpenClaw Pattern**: Skills are `SKILL.md` files with YAML frontmatter (name, description, requires, install). Discovered from multiple sources with precedence: bundled < plugin < managed (`~/.openclaw/skills/`) < workspace (`<workspace>/skills/`). Requirements declare binary dependencies (`requires: { bins: ["gh"] }`). Eligibility checker validates requirements at runtime. Summaries injected into system prompt as XML; full content loaded on demand.
- **Kukuvaia Gap**: `SkillsLoader` discovers from `.kukuvaia/skills/` only. No precedence ordering, no requirement declarations, no eligibility checking.
- **Spring Adaptation**: Enhance `SkillsLoader` with three-source discovery: (a) classpath `/skills/` (built-in), (b) `~/.kukuvaia/skills/` (user global), (c) `.kukuvaia/skills/` (project, highest precedence). Add `requires` field to `SkillSpec` with `SkillEligibilityChecker` that validates binary availability (`Runtime.exec("which " + bin)`), MCP server connectivity, API key presence. Project skills override user skills of the same name; user skills override built-in.
- **Effort**: Medium -- multiple loader changes, new checker service
- **Dependencies**: None. Builds on existing `SkillsLoader` and `SkillRegistry`.
- **Priority**: P1

```yaml
# Example SKILL.md frontmatter
---
name: github-pr
description: Create and manage GitHub pull requests
requires:
  bins: [gh]
  env: [GITHUB_TOKEN]
---
```

### 6. Doctor Command

- **OpenClaw Pattern**: `/doctor` command with 19+ diagnostic categories as modular `DoctorHealthContribution` objects. Four repair modes: read-only (default), `--yes` (accept defaults), `--repair` (apply fixes), `--repair --force` (aggressive). Plugins contribute checks via `PluginDoctorContractEntry`. Categories: config, auth, security, sandbox, sessions, memory, gateway, plugins.
- **Kukuvaia Gap**: Spring Boot Actuator health endpoint exists but no agent-specific diagnostics. No `/doctor` slash command. No repair capability.
- **Spring Adaptation**: Register `/doctor` as Tier 1 slash command in `CommandRouter`. Create `DoctorService` that collects all `HealthIndicator` beans. Add kukuvaia-specific indicators: `LlmProviderHealthIndicator` (ping Copilot/SmartGate), `DatabaseHealthIndicator` (PG connectivity + schema version), `MemoryHealthIndicator` (pgvector extension, embedding dimensions), `ToolRegistryHealthIndicator` (MCP tool availability), `SecurityAuditHealthIndicator` (dangerous flags, file permissions), `SessionIntegrityHealthIndicator` (orphaned sessions, lock status). Output as structured `TableBlock` with status/message per check.
- **Effort**: Medium -- new command, 5-6 health indicator beans, doctor service
- **Dependencies**: None. Spring Boot Actuator provides foundation.
- **Priority**: P1

```java
// Example health indicator
@Component
public class LlmProviderHealthIndicator implements HealthIndicator {
    private final LlmProviderService providerService;
    
    @Override
    public Health health() {
        try {
            providerService.pingActiveProvider();
            return Health.up()
                .withDetail("provider", providerService.getActiveProvider().name())
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("provider", providerService.getActiveProvider().name())
                .withException(e)
                .build();
        }
    }
}
```

### 7. Tool Safety Classification

- **OpenClaw Pattern**: Tool calls classified into `AcpApprovalClass`: `readonly_scoped` (auto-approved, CWD-scoped), `readonly_search` (auto-approved), `mutating` (requires approval), `exec_capable` (requires approval), `control_plane` (requires approval). Classification drives approval workflow.
- **Kukuvaia Gap**: No tool classification. All tools treated equally by the advisor chain.
- **Spring Adaptation**: Add `@ToolSafety` annotation with `SafetyLevel` enum (READ_ONLY, MUTATING, EXEC_CAPABLE, CONTROL_PLANE). Classification either via annotation on `@McpTool` methods or via configuration for user-defined tools. `ToolSafetyClassifier` service resolves safety level per tool invocation. Used by `ToolFilteringAdvisor` for policy enforcement. Future: approval workflow for MUTATING+ tools.
- **Effort**: Medium -- annotation, classifier service, advisor integration
- **Dependencies**: Tool policy groups (element 1) for deny-always-wins enforcement.
- **Priority**: P1

### 8. Trust Model Documentation

- **OpenClaw Pattern**: `SECURITY.md` documenting: one-user trust model, five trust boundaries with explicit controls, out-of-scope definitions (what is NOT a vulnerability), supported/unsupported configurations.
- **Kukuvaia Gap**: STRIDE security review exists but no formal trust model document. No out-of-scope definitions.
- **Spring Adaptation**: Create `SECURITY.md` at repository root following OpenClaw's template: (a) trust model (one Spring Boot instance per operator), (b) trust boundaries (API auth, session isolation, tool sanitization, daemon budget, sub-agent depth), (c) security controls per boundary, (d) out-of-scope (prompt injection alone, trusted operator actions, heuristic bypasses), (e) security contact information.
- **Effort**: Small -- documentation only
- **Dependencies**: None.
- **Priority**: P1

### 9. Advisor Chain Enrichment

- **OpenClaw Pattern**: 44 hook points with terminal semantics (`block: true` stops chain) and configurable fail-open/fail-closed per hook. Hooks at: config, normalization, auth, model resolution, tool resolution, memory retrieval, message processing, response generation.
- **Kukuvaia Gap**: ~8 advisors with fixed ordering and no terminal semantics. No fail-mode configuration.
- **Spring Adaptation**: Extend `BaseAdvisor` with `boolean isTerminal()` and `FailMode getFailMode()` (OPEN or CLOSED). Modify advisor chain execution in `ChatClientConfig` to check terminal flag after each advisor. Add advisor insertion points for tool resolution and memory retrieval. This may require custom `AdvisorChainRunner` if Spring AI's built-in chain does not support terminal semantics.
- **Effort**: Medium -- requires understanding Spring AI advisor internals
- **Dependencies**: None, but validate Spring AI supports this before committing.
- **Priority**: P2

### 10. Session Key Grammar

- **OpenClaw Pattern**: Structured session keys: `agent:<agentId>:<channel>:<chatType>:<chatId>[:thread:<threadId>]`. Channels can customize via `resolveSessionConversation()`.
- **Kukuvaia Gap**: Session keys are opaque strings. No structured encoding of agent, persona, or context.
- **Spring Adaptation**: `SessionKeyBuilder` utility producing structured keys: `kukuvaia:<persona>:<conversationId>[:sub:<subAgentId>][:daemon:<daemonId>]`. Used by `JdbcChatMemoryRepository` for session isolation. Backward-compatible: existing keys remain valid, new keys use structured format.
- **Effort**: Small -- utility class + migration for new sessions
- **Dependencies**: None.
- **Priority**: P2

---

## Architecture Compatibility Assessment

### Strengths: Where Kukuvaia Aligns Well

| OpenClaw Concept | Kukuvaia Equivalent | Compatibility |
|-----------------|---------------------|---------------|
| Hook system (44 points) | Spring AI advisor chain | Structural match. Advisors are ordered interceptors. |
| Plugin registry | Spring DI container | Native. `@Autowired List<T>` collects all implementations. |
| Workspace extensions | `.kukuvaia/` directory | Direct equivalent. Both use file-based discovery. |
| Manifest metadata | YAML frontmatter in TOOL.md/SKILL.md | Partial match. Needs consistency across all types. |
| In-process execution | Spring beans in same JVM | Native. Java classpath = in-process. |
| Session isolation | JdbcChatMemoryRepository | Built-in. PG-backed with conversation_id. |
| Multi-provider LLM | LlmProviderService | Both support multiple providers with routing. |
| Sub-agent isolation | SubAgentGuard + separate ChatClient | Both enforce isolation with tool filtering. |

### Tensions: Where Adaptation is Needed

| OpenClaw Concept | Tension | Resolution |
|-----------------|---------|------------|
| Dynamic plugin loading | Spring context is static after startup | Use `.kukuvaia/` hot-reload (ToolWatcher) for user extensions. Core extensions remain Spring beans. |
| TypeScript optional typing | Java's strict typing | Use interfaces with default methods for optional adapters. More boilerplate but safer. |
| 44 hook points | Spring AI has ~6 advisor positions | Enrich advisor chain incrementally. Not all 44 points are needed. |
| Plugin SDK as npm package | Java has Gradle/Maven modules | `kukuvaia-plugin-api` as separate Gradle module with published artifact. |
| Content wrapping with crypto.randomBytes | Java UUID | Java `UUID.randomUUID()` provides sufficient entropy for boundary markers. |

### Advantages Kukuvaia Has Over OpenClaw

| Area | Kukuvaia Advantage |
|------|-------------------|
| Agent orchestration | Embabel GOAP planning with A* search -- more sophisticated than OpenClaw's simpler agent model |
| Memory architecture | pgvector semantic search with 3 memory types -- more structured than OpenClaw's pluggable-but-simpler memory |
| Type safety | Java's strong typing catches errors at compile time that TypeScript misses at runtime |
| Concurrency | JVM thread model + Spring async more mature than Node.js event loop for agent workloads |
| Build system | Gradle multi-module provides clean module boundaries without JPMS complexity |

---

## Implementation Order

### Phase A: Security Hardening (2-3 days)

These four items have no dependencies, can be implemented in parallel, and deliver immediate security value.

```
A1. Tool policy groups + deny-always-wins  [P0, Small]
    Files: ToolPolicyProperties.java, ToolPolicyService.java
    Modify: ToolFilteringAdvisor.java
    Test: ToolPolicyServiceTest.java

A2. External content wrapping               [P0, Small]
    Files: ExternalContentWrappingAdvisor.java (or enhance ToolResultSanitizingAdvisor)
    Test: ExternalContentWrappingAdvisorTest.java

A3. Dangerous config naming                 [P0, Minimal]
    Modify: SecurityConfig.java (@PostConstruct warning)
    Modify: application.yml (rename existing props)

A4. Trust model documentation               [P1, Small]
    Files: SECURITY.md
```

### Phase B: Developer Experience (3-4 days)

These build on existing infrastructure with clear implementation paths.

```
B1. Prompt cache stability                  [P1, Small]
    Modify: AgentService.java (prompt assembly)
    Test: Verify deterministic prompt output

B2. Skills enrichment                       [P1, Medium]
    Modify: SkillsLoader.java, SkillSpec.java
    Files: SkillEligibilityChecker.java, SkillPrecedenceResolver.java
    Test: Multi-source discovery, requirement checking

B3. Doctor command                          [P1, Medium]
    Files: DoctorCommand.java, DoctorService.java
    Files: LlmProviderHealthIndicator.java, MemoryHealthIndicator.java,
           ToolRegistryHealthIndicator.java, SecurityAuditHealthIndicator.java
    Test: DoctorServiceTest.java
```

### Phase C: Architecture Enrichment (4-5 days)

These require more careful design and may touch core abstractions.

```
C1. Tool safety classification              [P1, Medium]
    Files: ToolSafety.java (annotation), SafetyLevel.java (enum),
           ToolSafetyClassifier.java
    Modify: ToolFilteringAdvisor.java
    Test: Classification correctness

C2. Session key grammar                     [P2, Small]
    Files: SessionKeyBuilder.java
    Modify: Session creation in AgentService
    Test: Key format, backward compatibility

C3. Advisor chain enrichment                [P2, Medium]
    Modify: BaseAdvisor.java (terminal, fail-mode)
    Modify: ChatClientConfig.java (chain execution)
    Test: Terminal semantics, fail-open/closed behavior
```

### Phase D: Future (When Needed)

```
D1. Unified extension metadata schema       [When skills pattern proven]
D2. Plugin SDK / kukuvaia-plugin-api         [When extension types stable]
D3. Exclusive slot system                    [When memory alternatives needed]
D4. Exec approval system                    [When shell tools added]
D5. Channel abstraction                     [When second transport needed]
```

---

## Risks and Mitigations

### R1: Spring AI Advisor Chain May Not Support Terminal Semantics
- **Risk**: Spring AI's built-in advisor chain may not allow an advisor to halt processing.
- **Impact**: Medium -- limits advisor enrichment (element 9).
- **Mitigation**: Investigate Spring AI internals before implementing. If unsupported, implement custom `AdvisorChain` wrapper that checks terminal flag. Alternatively, use exception-based flow control (advisor throws `ChainTerminatedException`).

### R2: Homoglyph Folding Coverage in Java
- **Risk**: `java.text.Normalizer.normalize(NFKC)` may not cover all 30+ bracket lookalikes that OpenClaw folds.
- **Impact**: Low -- reduces content wrapping effectiveness against sophisticated attacks.
- **Mitigation**: Start with NFKC normalization. Add custom character mapping for known lookalikes (Cyrillic, Greek, mathematical symbols) if NFKC is insufficient. OpenClaw's character list can serve as a test fixture.

### R3: Over-Engineering the Plugin SDK
- **Risk**: Attempting to build a full plugin SDK prematurely adds complexity without users to validate it.
- **Impact**: High -- wasted effort, increased maintenance burden.
- **Mitigation**: Implement patterns incrementally through skills enrichment first. Only create unified SDK when three or more extension types use the same metadata contract. Follow YAGNI.

### R4: Performance Impact of Additional Advisors
- **Risk**: Each advisor adds processing time to every request.
- **Impact**: Low -- advisors are lightweight compared to LLM round-trips.
- **Mitigation**: Profile advisor chain latency. OpenClaw runs 44 hooks without reported performance issues. Keep advisors simple (microsecond-level logic, no I/O in hot path).

### R5: Breaking Existing Extension Loaders
- **Risk**: Skills enrichment may break existing `.kukuvaia/skills/` that lack new metadata fields.
- **Impact**: Medium -- existing users lose functionality.
- **Mitigation**: All new metadata fields must be optional with sensible defaults. New features (precedence, requirements) activate only when metadata is present. Backward compatibility is non-negotiable.

---

## Conclusion

OpenClaw provides a mature reference architecture for agent platform extensibility and security. However, the most valuable transfers are not its large-scale abstractions (plugin SDK, channel system, marketplace) but its security primitives and contracts that kukuvaia can adopt incrementally.

**The three most impactful actions** are:
1. **Tool policy groups with deny-always-wins** -- closes the biggest security gap in kukuvaia's tool system
2. **External content wrapping** -- strengthens prompt injection defense with minimal effort
3. **Skills enrichment** -- delivers the most visible user-facing extensibility improvement

These build directly on kukuvaia's existing Spring AI advisor chain, `ToolResultSanitizingAdvisor`, and `SkillsLoader`. They require no architectural changes, only incremental additions.

**What to defer**: Channel abstraction, marketplace, unified plugin SDK, and ACP bridge address problems kukuvaia does not currently have. They should emerge organically when the need arises, not be pre-built based on another project's context.

**Key architectural insight**: kukuvaia's Spring AI advisor chain is structurally equivalent to OpenClaw's hook system. The path forward is enrichment (more insertion points, terminal semantics, fail-mode configuration), not replacement. This preserves kukuvaia's existing architecture while gaining the granularity that makes OpenClaw's extensibility production-grade.

**Estimated total effort for Phase A + B**: 5-7 development days, delivering security hardening, developer experience improvements, and a diagnostic system -- all with zero architectural disruption.

---

## Appendices

### A. Complete Source List

#### OpenClaw Sources (from 5 parallel agents)
- Plugin architecture: 12 files (architecture.md, manifest.md, sdk-overview.md, sdk-entrypoints.md, building-plugins.md, plugin-entry.ts, core.ts, provider-entry.ts, channel-contract.ts, contracts/registry.ts, slots.ts, hooks.ts)
- Security/trust: 15 files (SECURITY.md, security/index.md, sandboxing.md, THREAT-MODEL-ATLAS.md, formal-verification.md, external-content.ts, context-visibility.ts, dangerous-tools.ts, audit.ts, safe-regex.ts, allow-from.ts, ssrf-policy.ts, exec-approvals.ts, Dockerfile.sandbox)
- Channels/skills: 18 files (types.plugin.ts, types.core.ts, types.adapters.ts, registry.ts, resolve-route.ts, memory-state.ts, slots.ts, skill-contract.ts, local-loader.ts, workspace.ts, clawhub.ts, sdk-channel-plugins.md, channel-routing.md, pairing.md, hooks.md, healthcheck/SKILL.md, github/SKILL.md, VISION.md)
- DevX: 11 files (docs.acp.md, server.ts, translator.ts, session.ts, approval-classifier.ts, policy.ts, doctor.md, doctor-health.ts, doctor-health-contributions.ts, doctor-repair-mode.ts, doctor-contract-registry.ts)
- Extension manifests: 3 files (anthropic/openclaw.plugin.json, openai/openclaw.plugin.json, discord/openclaw.plugin.json)

#### Kukuvaia Sources
- Architecture docs: 12 files
- Standards: 15 files across 4 domains
- Source code: 65+ Java files, 1 Kotlin file
- Extension loaders, security classes, agent system

### B. Gaps and Uncertainties

| Gap | Type | Resolution Path |
|-----|------|----------------|
| OpenClaw performance data | Information gap | Benchmark kukuvaia advisor chain independently |
| OpenClaw SDK evolution history | Information gap | Not critical -- implement incrementally regardless |
| Spring AI terminal advisor support | Technical uncertainty | Investigation spike before implementing element 9 |
| Java homoglyph folding coverage | Technical uncertainty | Test NFKC against OpenClaw's character list |
| Plugin SDK timing | Strategic uncertainty | Defer until skills pattern proven (Phase D) |

### C. Findings Cross-Reference Matrix

| Pattern | Finding-01 | Finding-02 | Finding-03 | Finding-04 | Finding-05 |
|---------|-----------|-----------|-----------|-----------|-----------|
| Hook/Advisor | 44 hook points | Approval hooks | Automation hooks | ACP classifier | Advisor chain |
| Metadata-first | Plugin manifest | -- | Skill frontmatter | Doctor contrib | TOOL.md frontmatter |
| Deny-always-wins | Plugin allowlist | Tool policy deny | -- | -- | Tool filtering gap |
| Composition | Capability model | Trust layers | Channel adapters | Doctor modules | Extension points |
| Precedence | Plugin shadowing | -- | Skill precedence | -- | Single source only |
| Safety classification | -- | 3-level exec security | -- | 6-tier approval | None |
| Registry pattern | Plugin registry | -- | Channel registry | Doctor registry | Spring DI |
| Exclusive slot | Slot system | -- | Memory slot | -- | Memory module |

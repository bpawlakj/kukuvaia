# Synthesis: OpenClaw Architecture Transfer to Kukuvaia

## Research Question

Which architectural elements from OpenClaw are transferable to kukuvaia, and how should they be adapted to the Java/Spring AI stack?

## Executive Summary

OpenClaw provides a mature, plugin-centric architecture built around five core abstractions: a manifest-driven plugin SDK with typed capabilities, a five-layer security trust model, a channel/skill/memory composition system, and developer experience patterns including diagnostics and prompt cache stability. Cross-referencing these against kukuvaia's current architecture reveals that kukuvaia already has strong foundations (advisor chain, security layer, .kukuvaia extensibility, sub-agent isolation) but lacks the unifying contracts and safety infrastructure that make OpenClaw's extensibility production-grade.

The highest-value transfers are not individual features but architectural patterns that unify kukuvaia's existing 9 extension points under consistent contracts. Three patterns emerge as top priorities: (1) tool policy groups with deny-always-wins semantics, (2) external content wrapping with boundary markers, and (3) a markdown-first skills system with multi-source discovery. These build directly on existing kukuvaia infrastructure (advisor chain, ToolResultSanitizingAdvisor, SkillsLoader) and close the most impactful gaps.

Several OpenClaw elements are not transferable -- multi-channel routing, marketplace distribution, and ACP/IDE bridge address problems kukuvaia does not currently have. These belong in a future horizon.

## Cross-Source Analysis

### Validated Findings (Confirmed by Multiple Sources)

**V1: Hook/Advisor Pattern is the Central Extension Mechanism**
- Sources: Finding-01 (44 hook points, priority-ordered), Finding-03 (12 automation hook events), Finding-04 (approval classifier as hook), Finding-05 (Spring AI advisor chain)
- Confidence: HIGH
- Analysis: OpenClaw uses hooks everywhere -- plugin lifecycle, channel events, tool execution, automation, diagnostics. Kukuvaia's Spring AI advisor chain is the direct equivalent. The gap is not the mechanism but the granularity: OpenClaw has 44 hook points with terminal semantics and fail-open/closed configuration; kukuvaia has ~8 advisors with fixed ordering. The transfer path is to add more advisor insertion points and adopt terminal/fail-mode semantics on the existing chain.

**V2: Manifest/Metadata-First Pattern Recurs Across All Extension Types**
- Sources: Finding-01 (plugin manifest JSON), Finding-03 (SKILL.md frontmatter), Finding-04 (doctor contributions with id/kind), Finding-05 (kukuvaia TOOL.md with YAML frontmatter)
- Confidence: HIGH
- Analysis: Every OpenClaw extension type separates metadata from behavior. Plugin manifests validate before loading code. Skill frontmatter enables discovery without execution. Kukuvaia already uses this pattern for tools (TOOL.md) and skills (SKILL.md) but inconsistently -- personas, commands, and specialists lack structured metadata. Unifying all extension types under a common metadata contract is a high-value, low-risk improvement.

**V3: Exclusive Slot Pattern Applies to Memory and Potentially Other Singletons**
- Sources: Finding-01 (slot system with auto-disable), Finding-03 (memory as exclusive slot), Finding-05 (kukuvaia-memory as built-in module)
- Confidence: HIGH
- Analysis: Both projects treat memory as a singleton concern where only one implementation should be active. kukuvaia's `kukuvaia-memory` module with `SmartMemoryAdvisor` is effectively an exclusive slot already, but without the formal contract. Adding `@ExclusiveSlot` semantics on `@ConditionalOnMissingBean` would make this explicit and enable user-provided memory implementations to cleanly replace the built-in one.

**V4: Security is Layered, Not Monolithic**
- Sources: Finding-02 (five trust boundaries), Finding-05 (8 security classes with STRIDE review)
- Confidence: HIGH
- Analysis: Both projects use defense-in-depth. Kukuvaia covers TB1 (auth), TB2 (session isolation), and TB4 (tool result sanitization). OpenClaw adds TB3 (tool execution sandbox/approvals) and TB5 (supply chain). The gap is not architectural but coverage -- kukuvaia needs tool-level approval and sandbox capabilities as it expands tool access.

**V5: Tool Safety Classification is a Cross-Cutting Concern**
- Sources: Finding-02 (tool policy groups with deny-always-wins), Finding-04 (ACP approval classifier with 6 safety tiers), Finding-05 (persona-based tool filtering evolving to tag-based)
- Confidence: HIGH
- Analysis: OpenClaw classifies tools into safety tiers (readonly_scoped, mutating, exec_capable, control_plane) and applies group-based policies with deny-always-wins. Kukuvaia's tool filtering is currently name-based, planned to evolve to tag-based. The transfer: adopt group/tag-based classification with explicit safety levels and the deny-always-wins rule.

### Contradictions and Tensions

**T1: In-Process vs. Sandboxed Execution**
- Sources: Finding-01 (in-process trust with allowlisting), Finding-02 (Docker/SSH sandbox for tools)
- Tension: OpenClaw runs plugins in-process (like Java classpath) but sandboxes tool execution (unlike Spring). These are not contradictory but operate at different layers -- plugin code is trusted (allowlisted), but tool effects (file writes, shell commands) are sandboxed. Kukuvaia needs to maintain this distinction: Spring beans run in-process; tool side-effects should be sandboxable.

**T2: Personal Assistant vs. Multi-Channel Architecture**
- Sources: Finding-02 (one user per gateway), Finding-03 (10+ channel adapters for Discord, Slack, etc.)
- Tension: OpenClaw documents a personal assistant trust model (one user) but builds elaborate multi-channel infrastructure. The channel abstraction adds value for different input transports, not different users. For kukuvaia, the channel abstraction is premature -- HTTP/SSE is the only transport, and adding CLI as a second transport does not require the full channel adapter surface.

**T3: Convention-Based Safety vs. Code-Enforced Safety**
- Sources: Finding-04 (multi-agent safety as AGENTS.md instructions), Finding-02 (exec approvals as code-enforced)
- Tension: OpenClaw mixes convention-based safety (instructions to agents) with code-enforced safety (approval system). Both are needed: instructions set agent intent, code enforces hard limits. Kukuvaia's sub-agent system already uses both (prompt hardening + SubAgentGuard code). This validates kukuvaia's current approach.

### Confidence Assessment

| Finding | Confidence | Rationale |
|---------|------------|-----------|
| Tool policy groups + deny-always-wins | HIGH | Multiple sources, clear Spring mapping, addresses known gap |
| External content wrapping | HIGH | Specific implementation detail, addresses known injection risk |
| Skills with multi-source precedence | HIGH | Kukuvaia already has SkillsLoader, pattern is well-documented |
| Prompt cache stability | HIGH | Clear architectural technique, direct Spring AI mapping |
| Doctor/diagnostic command | HIGH | Spring Boot Actuator provides direct foundation |
| Exclusive slot pattern | MEDIUM | Conceptually clear but limited current need (only memory) |
| Plugin SDK / unified contract | MEDIUM | High value but large effort, needs careful scoping |
| Channel abstraction | LOW | Premature for kukuvaia's single-transport architecture |
| Marketplace/registry | LOW | Requires plugin SDK first, limited immediate user base |
| ACP bridge | LOW | IDE integration not on kukuvaia roadmap |

## Patterns and Themes

### P1: Composition over Inheritance (Prevalent, High Quality)
- Evidence: Channel adapters as ~25 optional typed fields (Finding-03), plugin capabilities as mix-and-match registrations (Finding-01), doctor contributions as flat objects (Finding-04)
- Kukuvaia alignment: Spring DI naturally supports this via interface injection. Advisor chain already works this way.
- Assessment: Kukuvaia follows this pattern where it uses Spring DI but breaks it in file-based extensions (monolithic loaders per type).

### P2: Registry as Central Discovery (Prevalent, Established)
- Evidence: Plugin registry (Finding-01), channel registry (Finding-03), skill registry (Finding-03), doctor contribution registry (Finding-04)
- Kukuvaia alignment: Has ToolCallbackProvider registry, SkillRegistry, CommandRegistry. Lacks unified extension registry.
- Assessment: Multiple independent registries are fine. A meta-registry is unnecessary. Each extension type needs its own typed registry.

### P3: Deny-Always-Wins Security (Consistent, Critical)
- Evidence: Tool policy deny overrides allow (Finding-02), sandbox deny paths (Finding-02), plugin deny list (Finding-01)
- Kukuvaia alignment: Not yet implemented. Tool filtering is inclusive (persona allows list) rather than exclusive (deny overrides).
- Assessment: Critical gap. Adding deny-always-wins semantics to ToolFilteringAdvisor is a small change with high security impact.

### P4: Metadata Before Behavior (Consistent, Established)
- Evidence: Plugin manifests (Finding-01), skill frontmatter (Finding-03), doctor contributions (Finding-04)
- Kukuvaia alignment: Partially implemented (TOOL.md, SKILL.md have YAML frontmatter). Inconsistent across extension types.
- Assessment: Extending metadata-first to all extension types improves discoverability and safety (validate config without executing code).

### P5: Escalating Verification (Emerging, Mature)
- Evidence: Build gates (dev/landing/CI in Finding-04), repair modes (read-only/yes/repair/force in Finding-04), exec security levels (deny/allowlist/full in Finding-02)
- Kukuvaia alignment: No formal verification levels. Tests run uniformly.
- Assessment: Vocabulary and process pattern, not code. Adopt the terminology for kukuvaia's CI/CD pipeline.

### P6: Static/Dynamic Prompt Separation (Specific, High Impact)
- Evidence: Cache boundary marker (Finding-04), stable prefix vs dynamic suffix (Finding-04)
- Kukuvaia alignment: Has static/dynamic boundary in system prompt construction but not optimized for cache stability.
- Assessment: Direct implementation in AgentService with minimal effort. Deterministic tool ordering + boundary marker.

## Key Insights

### I1: Kukuvaia's Advisor Chain IS the Hook System -- It Needs Enrichment, Not Replacement
- Evidence: OpenClaw's 44 hooks map to Spring AI advisors. Both are ordered interceptor chains around the request/response cycle.
- Implication: Instead of building a separate hook system, kukuvaia should enrich its advisor chain with: (a) more insertion points (before/after tool resolution, before/after memory retrieval), (b) terminal semantics (advisor can halt the chain), (c) configurable fail-open/fail-closed per advisor.
- Confidence: HIGH

### I2: The Biggest Gap is Not Features but Contracts
- Evidence: Kukuvaia has 9 extension points but no unified metadata schema, no capability declaration, no lifecycle management. OpenClaw has one plugin SDK that governs all extensions.
- Implication: A `kukuvaia-plugin-api` module defining the extension contract (metadata schema, capability interfaces, lifecycle hooks) would unify all 9 existing extension points under consistent patterns without replacing them. This is a refactoring, not a rewrite.
- Confidence: MEDIUM -- high value but also high effort; may be premature for current project stage.

### I3: Three Security Additions Deliver Outsized Value
- Evidence: (1) External content wrapping (Finding-02) addresses prompt injection via tool results -- kukuvaia has ToolResultSanitizingAdvisor but no boundary markers. (2) Tool policy groups (Finding-02) give persona-aware deny lists -- kukuvaia has basic tool filtering. (3) Dangerous config naming (Finding-02) prevents accidental security weakening.
- Implication: These three additions are small in implementation but large in security posture. They should be P0.
- Confidence: HIGH

### I4: Skills System is the Highest-ROI User-Facing Feature
- Evidence: OpenClaw's skills are markdown files with frontmatter, discovered from multiple sources, injected as summaries. Kukuvaia already has SkillsLoader and .kukuvaia/skills/ -- the gap is precedence ordering (built-in < user < project), dependency requirements, and eligibility checking.
- Implication: Enriching the existing skills system with OpenClaw's patterns (multi-source precedence, requirement declarations, eligibility checking) gives users the most visible extensibility improvement with minimal architectural change.
- Confidence: HIGH

### I5: Doctor Command Maps Naturally to Spring Boot Actuator
- Evidence: OpenClaw's 19+ diagnostic categories map to Spring Boot HealthIndicator interface. Repair modes map to admin endpoints. Plugin contributions map to HealthContributor SPI.
- Implication: A `/doctor` slash command that aggregates custom HealthIndicators (DB connectivity, LLM provider health, MCP tool status, memory readiness, session integrity) is straightforward to build and immediately useful for debugging.
- Confidence: HIGH

### I6: Channel Abstraction is Premature but Session Key Grammar is Not
- Evidence: Kukuvaia has one transport (HTTP/SSE). The full channel adapter surface (~25 optional fields) would be over-engineering. However, the session key grammar (encoding channel, chat type, threading into session identifiers) is useful now for properly isolating sessions across agents and personas.
- Implication: Adopt structured session key format without the full channel abstraction.
- Confidence: HIGH

## Relationships and Dependencies

### Dependency Chain

```
[1] Tool Policy Groups (deny-always-wins)
    No dependencies. Builds on existing ToolFilteringAdvisor.

[2] External Content Wrapping (boundary markers + homoglyph folding)
    No dependencies. Builds on existing ToolResultSanitizingAdvisor.

[3] Dangerous Config Naming Convention
    No dependencies. Documentation/naming convention.

[4] Prompt Cache Stability (boundary marker + deterministic ordering)
    No dependencies. Builds on existing AgentService prompt construction.

[5] Skills Enrichment (precedence, requirements, eligibility)
    No dependencies. Builds on existing SkillsLoader.

[6] Doctor Command (HealthIndicator aggregation)
    No dependencies. Builds on Spring Boot Actuator.

[7] Trust Model Documentation (SECURITY.md)
    No dependencies. Documentation.

[8] Unified Extension Metadata Schema
    DEPENDS ON: [5] (validate pattern with skills first)
    Prerequisite for: [9]

[9] Plugin SDK / kukuvaia-plugin-api Module
    DEPENDS ON: [8] (metadata schema)
    DEPENDS ON: All extension types stabilized

[10] Channel Abstraction
    DEPENDS ON: [9] (plugin SDK)
    Only when second transport needed

[11] Marketplace / Registry
    DEPENDS ON: [9] (plugin SDK)
    Only when user base warrants distribution
```

### Integration Points with Existing Architecture

- Tool policy groups integrate with: `ToolFilteringAdvisor`, `PersonaService`, `SubAgentGuard`
- External content wrapping integrates with: `ToolResultSanitizingAdvisor` (extends it)
- Prompt cache stability integrates with: `AgentService`, `ChatClientConfig`
- Skills enrichment integrates with: `SkillsLoader`, `SkillRegistry`, `SkillExecutor`
- Doctor command integrates with: `CommandRouter` (Tier 1), Spring Boot Actuator

## Gaps and Uncertainties

### Information Gaps

1. **OpenClaw's actual runtime performance characteristics** -- findings describe architecture, not performance. Unknown whether 44 hooks introduce measurable latency. For kukuvaia's advisor chain, performance testing needed when adding advisors.
2. **OpenClaw's migration path from simple to plugin SDK** -- findings show the mature state, not how it evolved. Unknown whether they started with a plugin SDK or retrofitted it onto existing extensions.
3. **Spring AI advisor chain extensibility limits** -- the advisor chain is designed for ordered interceptors, but terminal semantics (stop chain) and fail-mode configuration may require custom `AdvisorChainRunner` implementation.

### Unverified Claims

1. **`@ConditionalOnMissingBean` sufficiency for exclusive slots** -- assumed to work for memory slot replacement, but needs verification that user-provided beans can cleanly override built-in `SmartMemoryAdvisor`.
2. **Homoglyph folding feasibility in Java** -- OpenClaw uses a specific Unicode normalization for content wrapping. Java's `java.text.Normalizer` may or may not cover the same lookalike characters. Needs implementation spike.

### Unresolved Inconsistencies

1. **Plugin SDK scope vs. current project stage** -- the analysis identifies unified plugin SDK as high value, but kukuvaia is early-stage with limited users. A full SDK may be premature. Resolution: implement pattern incrementally via skills first, then extend to other types.

## Synthesis by Framework

### Technical Research Framework Application

**Component Analysis**:
- What exists: 9 extension points, advisor chain, security layer, memory module
- What's missing: unified contracts, tool policies, content wrapping, diagnostics
- How it integrates: All additions build on existing Spring mechanisms (advisors, DI, Actuator)

**Pattern Analysis**:
- Strongest patterns for transfer: deny-always-wins, metadata-first, composition-over-inheritance
- Weakest fit: multi-channel adapter surface, marketplace, formal verification (TLA+)
- Consistency: kukuvaia's existing patterns (advisor chain, DI, YAML frontmatter) align well with OpenClaw's approach

**Flow Analysis**:
- Tool call flow gains: policy check (deny-always-wins) before execution, content wrapping after execution
- System prompt flow gains: cache boundary marker, deterministic tool ordering
- Extension discovery flow gains: multi-source precedence, requirement validation

### Requirements Research Framework Application

**Need Analysis**:
- Stated: extensible agent platform, secure, multi-provider (from CLAUDE.md, roadmap)
- Implicit: production-grade security, user extensibility, developer productivity
- Priority: security hardening (P0), extensibility patterns (P1), developer experience (P2)

**Constraint Analysis**:
- Technical: Java 21 / Spring Boot 3.x / Spring AI 1.x stack, must preserve existing 9 extension points
- Resource: single-developer project, changes must be incremental and testable
- Architecture: multi-module Gradle, advisors must remain ordered and composable

## Conclusions

### Primary Conclusions

1. **Kukuvaia's Spring AI advisor chain is architecturally equivalent to OpenClaw's hook system** -- enrichment (more points, terminal semantics) is the right path, not replacement.
2. **Three security additions (tool policy groups, content wrapping, dangerous config naming) are the highest-value transfers** -- small effort, large security posture improvement.
3. **Skills enrichment is the highest-ROI user-facing transfer** -- builds on existing infrastructure, delivers visible extensibility improvement.
4. **Channel abstraction and marketplace are premature** -- they solve problems kukuvaia does not yet have.

### Secondary Conclusions

5. Doctor command via Spring Boot Actuator is a natural fit and immediately useful.
6. Prompt cache stability is a "free" optimization with clear implementation path.
7. Unified plugin SDK is valuable but should emerge incrementally from proven patterns, starting with skills.
8. Session key grammar (structured session IDs) delivers isolation benefits without channel abstraction overhead.

### Recommendations

Priority order: [1] Tool policy groups, [2] External content wrapping, [3] Dangerous config naming, [4] Prompt cache stability, [5] Skills enrichment, [6] Doctor command, [7] Trust model documentation, [8] Unified extension metadata (after skills prove the pattern).

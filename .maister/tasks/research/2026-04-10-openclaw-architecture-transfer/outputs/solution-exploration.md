# Solution Exploration: OpenClaw Architecture Transfer to Kukuvaia

## Problem Reframing

### Research Question

Which architectural elements from OpenClaw are transferable to kukuvaia and how should they be adapted to the Java/Spring AI stack?

### How Might We Questions

Based on the research findings, three key decision areas emerge where multiple viable approaches compete:

1. **HMW unify kukuvaia's 6 separate extension loaders** (tools, rules, skills, commands, personas, specialists) without breaking existing functionality or over-engineering for a single-developer project?
2. **HMW package and implement the 4 identified security additions** (tool policy groups, content wrapping, dangerous config, trust model) so they compound rather than fragment the security surface?
3. **HMW classify and govern tool safety** so that future mutating/exec-capable tools get appropriate guardrails without adding approval ceremony to every read-only tool call?

---

## Explored Alternatives

### Decision Area 1: Extension/Plugin Unification Strategy

How should kukuvaia's 6 separate extension loaders be unified or composed?

#### Alternative 1A: Leave As-Is with Metadata Enrichment

Add consistent YAML frontmatter to all extension types (personas, commands, specialists currently lack it) without changing loader architecture. Each loader remains independent. Metadata format is documented but not enforced by shared code.

**Strengths**:
- Zero risk to existing functionality -- purely additive
- No architectural refactoring required
- Each extension type evolves independently at its own pace
- Minimal maintenance burden: no shared abstraction to keep aligned
- Can start immediately; each frontmatter addition is an independent task

**Weaknesses**:
- Metadata format drift across types over time (no shared schema enforcement)
- No unified discovery: cannot query "what extensions are loaded?" from one place
- Duplicated validation logic across loaders
- Does not address the research finding that kukuvaia's biggest gap is "contracts, not features" (Insight I2)

**Best when**: The project remains very early-stage with few users, and extension types are still evolving rapidly. Premature abstraction would slow iteration.

**Evidence links**: Synthesis P4 (metadata-before-behavior is inconsistent), Synthesis I2 (biggest gap is contracts), Research Report Section 5 (skills enrichment as incremental improvement).

---

#### Alternative 1B: Shared Metadata Contract (Interface + Record)

Define a `ExtensionMetadata` record and `ExtensionDescriptor` interface in `kukuvaia-core`. All loaders parse frontmatter into this shared type. Loaders remain separate classes but share the parsing/validation contract. No unified registry -- each type keeps its own registry.

```
ExtensionMetadata(name, description, version, requires, tags, safetyLevel)
ExtensionDescriptor<T extends ExtensionMetadata> { T metadata(); boolean isEligible(); }
```

**Strengths**:
- Single source of truth for metadata shape -- prevents drift
- Shared validation logic (requirements checking, eligibility) written once
- Each loader retains autonomy for type-specific behavior
- Incremental: can migrate one loader at a time (start with skills, then personas, etc.)
- Directly addresses the "contracts not features" gap identified in research
- Natural stepping stone toward a plugin SDK if needed later

**Weaknesses**:
- Shared interface creates coupling between loaders that did not exist before
- `ExtensionMetadata` may need frequent changes as extension types evolve, creating ripple effects
- Slight over-engineering if only 2-3 extension types ever use complex metadata
- Requires refactoring existing `SkillSpec`, `PersonaSpec`, etc. to implement the shared interface

**Best when**: The project has stabilized its extension types and wants consistent discoverability and validation without the overhead of a full plugin SDK.

**Evidence links**: Synthesis V2 (manifest/metadata-first recurs across all types), Synthesis P4 (metadata-before-behavior pattern), Research Report element 8 (unified extension metadata schema as Phase D prerequisite).

---

#### Alternative 1C: Full Plugin SDK Module (kukuvaia-plugin-api)

Create a dedicated Gradle module `kukuvaia-plugin-api` that defines the complete extension contract: manifest schema, capability interfaces, lifecycle hooks (onLoad, onUnload, healthCheck), and a `PluginRegistry` that replaces all individual registries.

**Strengths**:
- Complete solution: all extension types governed by one SDK
- Enables external developers to build kukuvaia plugins with clear contract
- Lifecycle management (load/unload/health) enables doctor command integration
- Mirrors OpenClaw's proven approach at architectural level
- Strongest long-term foundation for an open-source ecosystem

**Weaknesses**:
- Large effort for a single-developer project with no external plugin authors yet
- High risk of premature abstraction: extension types are still evolving
- Adds a Gradle module to maintain, version, and document
- All existing loaders must be refactored to use the SDK simultaneously (big-bang migration risk)
- Research explicitly flags this as premature (Synthesis I2 MEDIUM confidence, Research R3 over-engineering risk)
- Creates API stability pressure: once published, changes break external consumers

**Best when**: Kukuvaia has a growing user/contributor base, extension types are stable, and external plugin distribution is a real need.

**Evidence links**: Synthesis I2 (high value but premature), Research R3 (over-engineering risk), Research Phase D (deferred until extension types stable), OpenClaw Finding-01 (plugin manifest + registry as mature-stage pattern).

---

#### Alternative 1D: Skills-First Pattern Extraction

Enrich the skills system fully (multi-source precedence, requirements, eligibility checking) as a proving ground. Extract successful patterns into shared utilities only after they are validated by real usage. No upfront shared interface or SDK -- let the abstraction emerge from concrete implementations.

**Strengths**:
- Follows YAGNI strictly: only abstracts what has been proven
- Skills system is the highest-ROI extension type to improve (Insight I4)
- Low risk: changes are contained to one extension type at a time
- If the extracted patterns work for skills, they can be offered to other loaders organically
- Matches the single-developer resource constraint (small scope at each step)

**Weaknesses**:
- Slower path to unified contracts: other extension types wait until skills patterns are proven
- Risk of "good enough" stasis: skills get enriched but extraction never happens
- Other extension types (personas, commands) may develop their own metadata patterns in the meantime, requiring reconciliation later
- Does not immediately address the discoverability gap for non-skill extensions

**Best when**: The developer wants to validate extensibility patterns before committing to shared abstractions, and skills improvement is already the next priority.

**Evidence links**: Synthesis I4 (skills as highest-ROI user-facing feature), Research element 5 (skills enrichment, P1), Research element 8 (unified metadata depends on skills proving the pattern), Synthesis dependency chain ([5] -> [8] -> [9]).

---

### Decision Area 2: Security Hardening Approach

How should the 4 security additions (tool policy groups, content wrapping, dangerous config, trust model) be packaged and implemented?

#### Alternative 2A: Incremental Advisor Additions (One at a Time)

Implement each security element as a separate, independent change. Tool policy groups enhance `ToolFilteringAdvisor`. Content wrapping enhances `ToolResultSanitizingAdvisor`. Dangerous config is a naming convention. Trust model is documentation. No shared "security framework" -- each is a standalone improvement.

**Strengths**:
- Each change is small, testable, and independently deployable
- No coupling between security additions: a bug in content wrapping does not affect tool policies
- Easy to reason about: each advisor has one job
- Matches kukuvaia's existing pattern (separate advisors for separate concerns)
- Lowest risk approach: any single addition can be reverted without affecting others
- Research confirms these have no dependencies on each other (Synthesis dependency chain)

**Weaknesses**:
- No unified security configuration surface: tool policies in one place, content wrapping in another, dangerous flags in a third
- Harder to audit holistically: must check multiple advisors/configs to understand security posture
- Doctor command (element 6) would need to discover and report on each security mechanism independently
- May lead to inconsistent security patterns across advisors over time

**Best when**: The developer values simplicity and wants to ship security improvements quickly without the overhead of designing a cohesive framework.

**Evidence links**: Synthesis I3 (three security additions deliver outsized value), Research Phase A (4 items with no dependencies, implementable in parallel), Research R4 (each advisor adds microsecond-level overhead).

---

#### Alternative 2B: Unified Security Configuration Surface

Create a single `kukuvaia.security.*` configuration namespace in `application.yml` that governs all security behaviors. A `SecurityConfigurationProperties` class binds the entire security surface. Individual advisors read from this shared configuration. Security audit (doctor command or startup log) reports the complete posture from one place.

```yaml
kukuvaia:
  security:
    tool-policies:
      default-deny: [group:dangerous]
    content-wrapping:
      enabled: true
      homoglyph-folding: true
    dangerously-disable-auth: false
    dangerously-skip-content-wrapping: false
    trust-model: single-operator
```

**Strengths**:
- Single place to see and configure all security settings
- Easy to audit: one YAML block, one properties class, one doctor report section
- Enables "security profile" presets (e.g., `profile: strict` vs `profile: relaxed` for development)
- Dangerous flags are naturally grouped with the features they disable
- Consistent naming and documentation across all security features

**Weaknesses**:
- Creates coupling: all security advisors depend on one configuration class
- Slightly more upfront work to design the configuration namespace
- Configuration class may grow unwieldy as security features expand
- Risk of "god configuration" anti-pattern if the namespace grows too large
- Any change to configuration structure affects all security components

**Best when**: The developer wants a clean, auditable security surface and is willing to invest in upfront namespace design.

**Evidence links**: Synthesis V4 (security is layered, not monolithic -- but layers should be visible), Research element 3 (dangerous config naming as convention), Research element 8 (trust model documentation).

---

#### Alternative 2C: Security Framework with Policy Engine

Build a `SecurityPolicyEngine` that evaluates security rules from a policy DSL or structured configuration. Tool policies, content wrapping decisions, and approval workflows are all expressed as policies evaluated by the engine. Advisors delegate to the engine rather than implementing logic directly.

**Strengths**:
- Maximum flexibility: new security rules added as policy entries, not code
- Separation of policy from mechanism: advisors enforce, engine decides
- Enables user-customizable security policies (e.g., workspace-specific tool restrictions)
- Aligns with OpenClaw's approach of configurable security at multiple levels
- Foundation for future approval workflows

**Weaknesses**:
- Significant over-engineering for the current project stage (4 security features do not justify an engine)
- Adds a DSL or complex configuration language to learn and maintain
- Policy evaluation adds latency and debugging complexity
- Single-developer maintenance burden for a policy engine is high
- No research evidence that kukuvaia needs this level of flexibility now
- Premature abstraction: the specific security behaviors are well-understood and can be hardcoded

**Best when**: The project has a complex, multi-tenant security model with user-defined policies. Not applicable to kukuvaia's single-operator model.

**Evidence links**: Synthesis T3 (convention-based vs code-enforced -- kukuvaia should use code enforcement), Research conclusion (incremental additions, not framework overhaul).

---

### Decision Area 3: Tool Safety and Approval Model

How should tool calls be classified and approved?

#### Alternative 3A: Static Allow/Deny Lists (Configuration-Only)

Define tool groups in `application.yml` with per-persona allow and deny lists. Deny always wins over allow. No runtime classification -- safety level is determined entirely by which group a tool belongs to. No approval workflow; denied tools are simply unavailable.

```yaml
kukuvaia:
  tools:
    groups:
      readonly: [search_items, get_classifications, get_outline_raw]
      mutating: [create_item, update_item, delete_item]
      dangerous: [shell_exec, process_run]
    personas:
      default:
        deny: [group:dangerous]
      admin:
        allow: [group:dangerous]
        deny: []  # deny still wins if both specified
```

**Strengths**:
- Simplest model: configuration-only, no runtime classification logic
- Deny-always-wins is easy to implement and reason about
- Matches OpenClaw's core pattern (tool policy groups with deny-always-wins)
- Very low maintenance burden: add a tool to a group, done
- Configuration is visible and auditable
- Builds directly on existing `ToolFilteringAdvisor` with minimal changes

**Weaknesses**:
- Static: every new tool must be manually classified in configuration
- No nuance within groups: all "mutating" tools treated equally regardless of risk
- No approval workflow: denied tools are invisible, not approvable case-by-case
- Cannot handle context-dependent safety (e.g., "read_file is safe for /tmp but dangerous for /etc")
- Group membership is global, not per-invocation

**Best when**: The tool set is small and well-known, and the developer wants the simplest possible safety model.

**Evidence links**: Research element 1 (tool policy groups, P0, Small effort), Synthesis P3 (deny-always-wins as critical gap), Synthesis V5 (classification drives approval -- but approval is separate from classification).

---

#### Alternative 3B: Annotation-Based Classification with Tiered Response

Add a `@ToolSafety(level = SafetyLevel.MUTATING)` annotation to `@McpTool` methods. A `ToolSafetyClassifier` service reads the annotation (or falls back to configuration for user-defined tools). Different safety levels trigger different responses: READ_ONLY auto-approved, MUTATING logged with parameter summary, EXEC_CAPABLE requires explicit configuration opt-in, CONTROL_PLANE denied by default.

```java
public enum SafetyLevel {
    READ_ONLY,      // Auto-approved, no special handling
    MUTATING,       // Logged, approved by default policy
    EXEC_CAPABLE,   // Requires explicit opt-in in configuration
    CONTROL_PLANE   // Denied unless dangerously-allow-control-plane=true
}
```

**Strengths**:
- Classification is co-located with tool definition (annotation on the method)
- Tiered response allows proportional governance (not all-or-nothing)
- Extensible: new safety levels can be added as tool capabilities grow
- Integrates with deny-always-wins (configuration deny overrides annotation allow)
- Logging at MUTATING level provides audit trail without blocking
- Aligns with OpenClaw's 6-tier `AcpApprovalClass` but simplified to 4 tiers
- Future-proof: approval workflows can hook into tiers later

**Weaknesses**:
- More implementation effort than pure configuration (annotation, enum, classifier service)
- Annotation only works for in-process `@McpTool` methods, not external MCP tools (need config fallback)
- Classification at definition time cannot capture runtime context (path, parameters)
- Enum-based tiers may be too rigid: real tools often span categories
- Maintenance: every new tool needs correct annotation (forgetting defaults to what?)

**Best when**: The tool set is growing, tools have well-defined safety characteristics, and the developer wants proportional governance without full approval workflows.

**Evidence links**: Research element 7 (tool safety classification, P1, Medium), Synthesis V5 (classification as cross-cutting concern), OpenClaw Finding-04 (6-tier approval classifier as proven pattern).

---

#### Alternative 3C: Dynamic Classification with Approval Workflow

Tool calls are classified at invocation time based on method annotation, configuration, AND runtime parameters (e.g., file path, target URL). A `ToolApprovalService` checks the resolved safety level against the current policy and either auto-approves, logs-and-approves, or blocks-and-requests-user-approval (via SSE prompt to CLI).

**Strengths**:
- Most sophisticated: captures context-dependent risk (e.g., writing to /etc vs /tmp)
- User gets informed consent for risky operations
- Aligns with OpenClaw's exec approval system fully
- Maximum security coverage: even approved tools can be blocked for specific parameters
- Audit trail includes both classification and approval decision

**Weaknesses**:
- Significantly more complex than needed for the current tool set
- Approval workflow interrupts agent flow (user must respond before tool executes)
- Requires SSE protocol extension for approval prompts (CLI must handle them)
- Parameter-based classification needs per-tool rules (cannot generalize easily)
- High maintenance burden for a single developer
- Research explicitly defers exec approval system to Phase D (when shell tools are added)
- Kukuvaia currently has no shell/file tools that would trigger approval

**Best when**: The platform supports dangerous tools (shell exec, file system write, network access) and users need informed consent before execution.

**Evidence links**: Research element 7 (tool safety classification, but approval deferred to Phase D), Synthesis T1 (in-process trust vs sandboxed execution -- approval relevant when sandbox needed), Research Tier 3 (exec approval as deferred item).

---

#### Alternative 3D: Hybrid Static Groups + Annotation Classification

Combine Alternative 3A and 3B: tool groups in configuration provide the policy enforcement layer (allow/deny with deny-always-wins), while `@ToolSafety` annotations provide the classification layer (READ_ONLY/MUTATING/etc.). Groups can reference safety levels (`deny: [safety:exec_capable]`) in addition to named tools and named groups.

**Strengths**:
- Separation of concerns: annotation classifies, configuration governs
- Groups provide coarse-grained control; annotations provide fine-grained metadata
- Deny-always-wins applies at the group/policy level regardless of annotation
- Configuration can override annotation-based defaults (e.g., allow a MUTATING tool for a specific persona)
- Incremental: start with groups only (3A), add annotations later
- Most flexible without being over-engineered

**Weaknesses**:
- Two systems to understand: groups AND safety levels
- Potential confusion about which layer takes precedence (answer: deny-always-wins at group level, annotations are metadata)
- More documentation needed to explain the two-layer model
- External MCP tools lack annotations, relying solely on configuration groups

**Best when**: The developer wants to start simple (groups) but have a clear upgrade path to richer classification (annotations) without rearchitecting.

**Evidence links**: Research elements 1 and 7 (tool policy groups P0 + tool safety classification P1), Synthesis V5 (classification as cross-cutting concern), Synthesis dependency chain ([1] no deps, [7] depends on [1]).

---

## Trade-Off Analysis

### Decision Area 1: Extension/Plugin Unification

| Perspective | 1A: Leave As-Is | 1B: Shared Contract | 1C: Full SDK | 1D: Skills-First |
|-------------|-----------------|---------------------|--------------|------------------|
| **Technical Feasibility** | High (no work) | High (interface + record) | Medium (Gradle module, migration) | High (one loader at a time) |
| **User Impact** | Low (no improvement) | Medium (consistent discovery) | High (external plugin support) | Medium (skills improve first) |
| **Simplicity** | High (nothing changes) | Medium (shared interface) | Low (SDK complexity) | High (incremental) |
| **Risk** | None | Low (interface may need revision) | High (premature, big-bang) | Low (contained scope) |
| **Scalability** | Low (drift over time) | High (shared contract scales) | High (full SDK scales) | Medium (must extract later) |
| **One-Dev Burden** | None | Low (small interface) | High (module + docs + versioning) | Low (one extension at a time) |

### Decision Area 2: Security Hardening

| Perspective | 2A: Incremental | 2B: Unified Config | 2C: Policy Engine |
|-------------|-----------------|--------------------|--------------------|
| **Technical Feasibility** | High (small changes) | High (Spring properties) | Medium (DSL/engine) |
| **User Impact** | Medium (security improves) | Medium (auditability) | Medium (customizable) |
| **Simplicity** | High (each advisor standalone) | Medium (one config namespace) | Low (engine + DSL) |
| **Risk** | Low (independent, revertible) | Low (config coupling only) | High (over-engineering) |
| **Scalability** | Medium (ad hoc growth) | High (namespace extensible) | High (policy-driven) |
| **One-Dev Burden** | Low (4 small tasks) | Low-Medium (namespace design) | High (engine maintenance) |

### Decision Area 3: Tool Safety

| Perspective | 3A: Static Lists | 3B: Annotations | 3C: Dynamic Approval | 3D: Hybrid |
|-------------|-------------------|------------------|----------------------|------------|
| **Technical Feasibility** | High (config only) | High (annotation + service) | Medium (SSE extension needed) | High (incremental) |
| **User Impact** | Low (binary allow/deny) | Medium (proportional) | High (informed consent) | Medium (layered) |
| **Simplicity** | High (YAML groups) | Medium (annotation + config) | Low (approval flow) | Medium (two layers) |
| **Risk** | Low (simple model) | Low (static classification) | Medium (flow interruption) | Low (incremental) |
| **Scalability** | Medium (manual per tool) | High (annotation auto-discovers) | High (context-aware) | High (two layers complement) |
| **One-Dev Burden** | Low (config changes) | Low-Medium (annotation per tool) | High (approval + SSE + CLI) | Low-Medium (phased) |

---

## User Preferences

Derived from project constraints (not from direct dialogue):

- **Single developer** -- maintenance burden is a primary filter
- **Early-to-mid stage project** -- premature abstraction is risky
- **Open source (Apache 2.0)** -- extensibility matters long-term, but no plugin ecosystem exists yet
- **Security-conscious** -- STRIDE review already done, 8 security classes implemented, security standards documented
- **Incremental approach** -- the project's own standards emphasize "stability first", "safe transitions", "minimal impact"
- **Spring AI alignment** -- solutions should leverage existing Spring mechanisms, not fight them

---

## Recommended Approach

### Decision Area 1: Alternative 1D -- Skills-First Pattern Extraction

**Recommendation**: Enrich the skills system fully as a proving ground, then extract successful patterns.

**Primary rationale**: The research identifies skills as the highest-ROI user-facing feature (Insight I4), and the dependency chain explicitly sequences unified metadata after skills prove the pattern ([5] -> [8] -> [9]). For a single-developer project in early-to-mid stage, validating patterns on one extension type before committing to shared abstractions follows YAGNI and matches the project's "build only what is needed" standard.

**Key trade-offs accepted**: Other extension types (personas, commands, specialists) will not immediately benefit from enriched metadata. There is a risk that extraction never happens and each loader develops its own divergent pattern. This is acceptable because the cost of premature unification exceeds the cost of delayed unification at this project stage.

**Key assumptions**:
1. Skills will be the most actively extended type by users in the near term
2. Patterns validated in skills (precedence, requirements, eligibility) will generalize to other extension types
3. The developer will commit to extracting shared patterns once skills are stable (mitigated by making this a documented decision, not just a hope)

**Confidence**: HIGH -- directly supported by multiple research findings and aligns with project constraints.

**Upgrade path**: After skills enrichment is stable and battle-tested, move to Alternative 1B (shared metadata contract) by extracting the proven patterns into an interface. This avoids the premature leap to 1C (full SDK) while still converging on consistent contracts.

---

### Decision Area 2: Alternative 2A -- Incremental Advisor Additions, with 2B Configuration Namespace

**Recommendation**: Implement each security element independently (2A) but organize configuration under a consistent `kukuvaia.security.*` namespace from the start (borrowing the best element of 2B).

**Primary rationale**: The research confirms all 4 security additions have zero dependencies on each other and can be implemented in parallel. Keeping them as separate advisors preserves kukuvaia's existing architectural pattern and makes each change independently testable and revertible. However, organizing configuration under a shared namespace is low-cost and prevents configuration sprawl.

**Key trade-offs accepted**: There is no unified "security policy" abstraction -- each security mechanism is a separate advisor with its own logic. This means no security profile presets and no single switch to "harden everything." This is acceptable because the 4 security features are small and well-understood enough that a single YAML section provides sufficient auditability.

**Key assumptions**:
1. The 4 security additions are sufficient for the current threat model (validated by STRIDE review)
2. Spring AI advisor chain performance is not materially impacted by 2-3 additional advisors (Research R4 confirms microsecond overhead)
3. The `kukuvaia.security.*` namespace will remain manageable as security features grow

**Confidence**: HIGH -- all 4 items have clear Spring mappings, small implementation effort, and high security impact.

---

### Decision Area 3: Alternative 3D -- Hybrid Static Groups + Annotation Classification (phased)

**Recommendation**: Start with static groups (3A) as Phase A, add annotation-based classification (the annotation layer from 3B) as Phase C.

**Primary rationale**: The research identifies tool policy groups as P0 (no dependencies, immediately impactful) and tool safety classification as P1 (depends on policy groups). The hybrid approach naturally maps to the phased implementation plan: groups first for immediate deny-always-wins protection, annotations later for richer classification metadata. This avoids the over-engineering of dynamic approval (3C) while providing a clear upgrade path.

**Key trade-offs accepted**: No runtime approval workflow -- tools are either available or not. Users do not get "approve this dangerous operation?" prompts. This is acceptable because kukuvaia currently has no shell/file tools that would warrant approval, and the research explicitly defers exec approvals to Phase D.

**Key assumptions**:
1. Static groups are sufficient for the current tool set (read-only database and API tools)
2. When mutating or exec-capable tools are added (e.g., document agent phase), annotations will be added to classify them
3. The deny-always-wins semantics in groups will be robust enough that annotation-based classification does not weaken it
4. External MCP tools (which lack annotations) will be adequately governed by configuration-only groups

**Confidence**: HIGH for the groups layer (Phase A). MEDIUM for the annotation layer (Phase C) -- may need adjustment when actual mutating tools are built.

---

## Why Not Others

### Decision Area 1

- **1A (Leave As-Is)**: Fails to address the "contracts not features" gap (Insight I2). Metadata drift across extension types becomes technical debt. Acceptable short-term but not a deliberate strategy.
- **1B (Shared Contract)**: Good approach but premature without a proven pattern to extract. Designing the interface before knowing what skills enrichment reveals risks creating the wrong abstraction. This is the recommended next step after 1D succeeds.
- **1C (Full SDK)**: Over-engineering for a single-developer, early-stage project with no external plugin authors. Research explicitly flags this as premature (R3) and the synthesis gives it MEDIUM confidence. Correct for a mature project with an ecosystem; wrong for kukuvaia today.

### Decision Area 2

- **2B (Unified Config Surface)**: The configuration namespace aspect is adopted, but the unified `SecurityConfigurationProperties` class creating coupling between all security advisors is rejected. Each advisor should own its own configuration sub-namespace to maintain independence.
- **2C (Policy Engine)**: Significant over-engineering. Four well-understood security features do not justify a policy DSL or evaluation engine. The maintenance burden for a single developer would consume time better spent on the security features themselves.

### Decision Area 3

- **3A (Static Lists Only)**: Good and recommended as the starting point, but stopping here means every new tool requires manual group assignment with no metadata-level classification. The annotation layer (from 3D) provides richer tooling as the tool set grows.
- **3B (Annotations Only)**: Classification without the deny-always-wins group layer lacks the coarse-grained policy enforcement that is the highest-priority security gap. Annotations alone do not provide deny-always-wins semantics.
- **3C (Dynamic Approval)**: Requires SSE protocol extension, CLI changes, and user interaction flow for a feature whose prerequisite tools (shell exec, file write) do not exist yet. Research defers this to Phase D. Building the approval infrastructure now would be speculative engineering.

---

## Deferred Ideas

### Stretch (Related, Worth Tracking)

1. **Exclusive slot system for memory**: kukuvaia-memory's `SmartMemoryAdvisor` is effectively an exclusive slot. Formalizing this with `@ConditionalOnMissingBean` semantics would enable user-provided memory implementations. Deferred because only memory needs it currently. Track for when a second singleton concern appears.

2. **Advisor chain terminal semantics**: Adding `isTerminal()` and `FailMode` to advisors would bring kukuvaia closer to OpenClaw's 44-hook flexibility. Deferred because it may require custom `AdvisorChainRunner` and the current ~8 advisors do not need terminal semantics. Track for Phase C.

3. **Session key grammar**: Structured session keys (`kukuvaia:<persona>:<conversationId>`) would improve session isolation without full channel abstraction. Small effort but low immediate need. Track for Phase C.

### Out-of-Scope (Separate Concerns)

4. **Channel abstraction**: OpenClaw's ~25-field channel adapter surface solves multi-transport routing. Kukuvaia has one transport (HTTP/SSE). Not applicable until a second transport is genuinely needed.

5. **ClawHub marketplace / plugin distribution**: Requires plugin SDK + user community. No distribution need exists.

6. **ACP/IDE bridge**: IDE integration is not on kukuvaia's roadmap. Different interaction model.

7. **Formal verification (TLA+)**: Aspirational. Integration tests are the pragmatic equivalent for kukuvaia's scale.

8. **Exec sandbox backends (Docker/SSH)**: Relevant only when shell/file tools exist. Deferred to document agent phase.

No additional out-of-scope ideas were identified beyond those already classified by the research.

---

## Implementation Sequence Summary

Based on the recommended approaches across all three decision areas, the implementation naturally sequences as:

| Phase | Items | Estimated Effort | Decision Area |
|-------|-------|-----------------|---------------|
| **A** (Security) | Tool policy groups (deny-always-wins), content wrapping, dangerous config naming, trust model doc | 2-3 days | DA2 (incremental) + DA3 (groups layer) |
| **B** (Experience) | Skills enrichment (precedence, requirements, eligibility), prompt cache stability, doctor command | 3-4 days | DA1 (skills-first) |
| **C** (Enrichment) | Tool safety annotations, session key grammar, advisor chain enrichment | 4-5 days | DA3 (annotation layer) |
| **D** (Future) | Shared metadata contract extraction, plugin SDK, exec approval, sandbox | When needed | DA1 (upgrade from 1D to 1B) |

Total estimated effort for Phases A+B: **5-7 development days**, delivering security hardening, skills extensibility, and diagnostics with zero architectural disruption.

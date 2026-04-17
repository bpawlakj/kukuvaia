# Decision Log

## ADR-001: Extension/Plugin Unification Strategy

**Status**: Accepted
**Date**: 2026-04-10

### Context

Kukuvaia has 9 separate extension points (tools, rules, skills, commands, personas, specialists, advisor chain, @Tool methods, Embabel agents) each with their own loader and metadata format. OpenClaw unifies all extensions under a single plugin SDK with manifest-driven contracts. The research identifies that kukuvaia's biggest gap is "contracts, not features" (Synthesis Insight I2) -- the extension mechanisms exist but lack consistent metadata schemas, capability declarations, and lifecycle management. Four approaches were considered: leave as-is with metadata enrichment, shared metadata contract, full plugin SDK module, and skills-first pattern extraction.

### Decision Drivers

- Single-developer project: maintenance burden of shared abstractions must be justified by proven value
- Early-to-mid stage: extension types are still evolving; premature abstraction risks building the wrong contract
- Skills identified as highest-ROI user-facing extension type (Synthesis Insight I4)
- Research dependency chain: unified metadata schema depends on skills proving the pattern first ([5] -> [8] -> [9])
- Project standards emphasize "build only what is needed" and "minimal implementation"

### Considered Options

1. **Leave As-Is with Metadata Enrichment** -- add YAML frontmatter to all types, no shared code
2. **Shared Metadata Contract** -- define `ExtensionMetadata` record and `ExtensionDescriptor` interface in kukuvaia-core
3. **Full Plugin SDK Module** -- create `kukuvaia-plugin-api` Gradle module with manifest schema, capability interfaces, lifecycle hooks
4. **Skills-First Pattern Extraction** -- enrich skills fully, extract shared patterns only after validation

### Decision Outcome

Chosen option: **Skills-First Pattern Extraction (Option 4)**, because it follows YAGNI by validating extensibility patterns on the highest-value extension type before committing to shared abstractions. Skills enrichment (multi-source precedence, requirement declarations, eligibility checking) is immediately useful and contained in scope. Once the patterns prove stable, they can be extracted into a shared contract (Option 2) as a natural next step without speculative design.

### Consequences

#### Good
- Lowest risk: changes contained to one extension type at a time
- Skills improvement delivers immediate user value (visible extensibility)
- Validates metadata patterns before locking them into shared interfaces
- Matches single-developer resource constraint (small scope per step)
- Clear upgrade path: skills-first -> shared contract -> plugin SDK (if ever needed)

#### Bad
- Other extension types (personas, commands, specialists) do not immediately benefit from enriched metadata
- Risk of "good enough" stasis where extraction never happens and loaders diverge further
- Slower path to unified contracts compared to direct shared interface approach

#### Neutral
- Requires discipline to actually extract patterns after skills stabilize (documented as explicit future phase)
- Other extension types can still add YAML frontmatter independently without waiting for shared contract

---

## ADR-002: Security Hardening Approach

**Status**: Accepted
**Date**: 2026-04-10

### Context

Four security additions from OpenClaw are identified as high-value transfers: (1) tool policy groups with deny-always-wins semantics, (2) external content wrapping with boundary markers and Unicode normalization, (3) dangerous config flag naming convention, and (4) trust model documentation. The question is whether to implement these as independent changes, under a unified configuration framework, or via a security policy engine. All four items have zero dependencies on each other and can be implemented in parallel.

### Decision Drivers

- Research confirms all 4 items are independent with no inter-dependencies (Synthesis dependency chain)
- Kukuvaia's existing pattern uses separate advisors for separate concerns
- Each change should be independently testable and revertible
- Configuration auditability matters for security -- all security settings should be findable in one place
- Spring Boot `@ConfigurationProperties` naturally supports hierarchical namespaces
- Single-developer project cannot sustain a policy engine's maintenance burden

### Considered Options

1. **Incremental Advisor Additions** -- each security element as a separate, independent change with no shared framework
2. **Unified Security Configuration Surface** -- single `kukuvaia.security.*` namespace with `SecurityConfigurationProperties` binding all security behaviors
3. **Security Framework with Policy Engine** -- `SecurityPolicyEngine` evaluating security rules from policy DSL, advisors delegate to engine

### Decision Outcome

Chosen option: **Incremental Advisors + Config Namespace (Options 1 and 2 combined)**, because each security addition is implemented as an independent advisor (matching kukuvaia's existing architectural pattern) while configuration is organized under a shared `kukuvaia.security.*` namespace for auditability. This avoids coupling advisor implementations through a shared configuration class -- each advisor owns its own configuration sub-namespace (e.g., `kukuvaia.security.tool-policies.*`, `kukuvaia.security.content-wrapping.*`). The namespace provides the discoverability benefit of Option 2 without the coupling risk.

### Consequences

#### Good
- Each security addition is independently deployable, testable, and revertible
- No coupling between security advisors: a bug in content wrapping does not affect tool policies
- Configuration auditability via shared `kukuvaia.security.*` namespace
- Matches kukuvaia's established advisor-per-concern pattern
- Dangerous config flags naturally live in the security namespace alongside the features they disable

#### Bad
- No unified security policy abstraction: cannot express complex cross-cutting security rules
- No "security profile" presets (e.g., `profile: strict` vs `profile: relaxed`)
- Doctor command must discover and report on each security mechanism independently

#### Neutral
- Each advisor uses its own `@ConfigurationProperties` sub-class rather than one monolithic properties class
- The namespace convention is a naming agreement, not an enforced contract

---

## ADR-003: Tool Safety and Approval Model

**Status**: Accepted
**Date**: 2026-04-10

### Context

OpenClaw classifies tool calls into safety tiers (readonly_scoped, mutating, exec_capable, control_plane) and applies group-based policies with deny-always-wins. Kukuvaia's tool filtering is currently name-based per persona with no safety classification, no deny lists, and no deny-always-wins rule. The tool set is currently small (read-only database and API tools) but will grow as the document agent phase adds mutating and potentially exec-capable tools. The question is how to classify and govern tool safety: static configuration only, annotation-based classification, dynamic runtime approval, or a hybrid approach.

### Decision Drivers

- Tool policy groups are the highest-priority security gap (P0, Synthesis Pattern P3)
- Tool safety classification is P1 and depends on policy groups being in place
- Current tool set is small and read-only: approval workflows would be over-engineering
- Future tool set will include mutating tools (document agent phase): classification infrastructure should be ready
- Annotations co-locate classification with tool definition for in-process `@McpTool` methods
- External MCP tools cannot have annotations: configuration fallback is required
- Deny-always-wins must be enforced at the policy level regardless of classification

### Considered Options

1. **Static Allow/Deny Lists** -- configuration-only groups with deny-always-wins, no runtime classification
2. **Annotation-Based Classification** -- `@ToolSafety` annotation with tiered response (READ_ONLY, MUTATING, EXEC_CAPABLE, CONTROL_PLANE)
3. **Dynamic Classification with Approval Workflow** -- runtime parameter-based classification with user approval via SSE
4. **Hybrid Static Groups + Annotations** -- groups for policy enforcement (Phase A), annotations for classification metadata (Phase C)

### Decision Outcome

Chosen option: **Hybrid Static Groups + Annotations, phased (Option 4)**, because it separates policy enforcement (groups, deny-always-wins) from classification metadata (annotations) and maps naturally to the phased implementation plan. Phase A delivers immediate deny-always-wins protection via configuration groups. Phase C adds richer classification metadata via `@ToolSafety` annotations on `@McpTool` methods, with configuration fallback for external MCP tools. Groups can reference safety levels (`deny: [safety:exec_capable]`) in addition to named tools and named groups, creating a layered governance model.

### Consequences

#### Good
- Immediate security value in Phase A with minimal effort (configuration-only groups)
- Clear upgrade path from simple groups to rich classification without rearchitecting
- Separation of concerns: annotations classify, configuration governs
- Deny-always-wins applies at the group/policy level regardless of annotation
- External MCP tools adequately governed by configuration groups even without annotations
- Proportional governance when annotations are added: READ_ONLY auto-approved, EXEC_CAPABLE requires opt-in

#### Bad
- Two systems to understand: groups AND safety levels (after Phase C)
- Every new in-process tool should have a `@ToolSafety` annotation (forgetting it needs a default -- TBD, likely READ_ONLY)
- Configuration groups must be maintained for external MCP tools that cannot have annotations
- No runtime approval workflow: denied tools are invisible, not approvable case-by-case

#### Neutral
- Dynamic approval (Option 3) is explicitly deferred to Phase D, when shell/file tools are added
- The annotation layer may need adjustment when actual mutating tools are built (Phase C is marked MEDIUM confidence)
- External MCP tools rely solely on configuration groups, creating an asymmetry with annotated in-process tools

# Research Plan: OpenClaw Architecture Transfer to Kukuvaia

## Research Overview

### Research Question
Which architectural elements from OpenClaw are transferable to kukuvaia and how to adapt them to the Java/Spring AI stack?

### Research Type
**Mixed** -- technical codebase analysis (OpenClaw patterns and abstractions), requirements gathering (what kukuvaia needs and lacks), and best practices assessment (how to implement discovered patterns in Java/Spring AI ecosystem).

### Scope and Boundaries

**In scope:**
- OpenClaw plugin architecture: SDK, manifest system, loader pipeline, registry, capability model
- OpenClaw security model: operator trust boundaries, sandbox backends, security audit command, approval system
- OpenClaw channel abstraction: messaging channel plugins, allowlists, routing, session binding
- OpenClaw skills and extensibility: skills directory, ClawHub marketplace, hooks system, memory plugins
- OpenClaw ACP/IDE integration: Agent Client Protocol bridge, session mapping, approval classifier
- OpenClaw multi-agent safety: concurrent agent work rules, stash/branch guards, scoped commits
- OpenClaw doctor/diagnostic: config migration, health checks, repair steps, deep scan
- OpenClaw build/test gates: gate terminology (dev gate, landing gate, CI gate), verification levels
- OpenClaw prompt cache stability patterns
- Kukuvaia current architecture: Spring AI ChatClient, advisor chains, MCP tools, personas, memory system, sub-agents, daemons

**Out of scope:**
- TypeScript-specific patterns (ESM, Bun, dynamic imports, jiti loader)
- Platform-specific code (macOS, iOS, Android apps)
- CI/CD and release automation details
- Direct code porting

**Constraints:**
- Target: Java 21+ / Spring Boot 3.x / Spring AI 1.x / Kotlin 2.x (Embabel)
- CLI: Go 1.22+ / Charm stack (separate repo, thin client)
- Must preserve kukuvaia's existing architecture decisions (advisor chains, MCP, personas)
- Open source, Apache 2.0

---

## Methodology

### Primary Approach
Multi-source comparative architecture analysis -- systematically map OpenClaw abstractions to kukuvaia's Spring ecosystem, evaluating transferability and adaptation strategy for each pattern.

### Analysis Framework
For each OpenClaw architectural element:
1. **Understand** -- What is the pattern and why does it exist?
2. **Evaluate** -- Does kukuvaia need this? What problem does it solve?
3. **Map** -- What is the Spring/Java equivalent? Does one already exist?
4. **Adapt** -- How would the concept be implemented in kukuvaia's stack?
5. **Assess** -- Effort vs. value, priority, dependencies

### Fallback Strategies
- If OpenClaw codebase is too large to read exhaustively, focus on AGENTS.md boundary guides, docs/, and type definition files
- If a pattern has no clear Spring equivalent, research Spring SPI, Spring Plugin, and OSGi patterns
- If kukuvaia architecture docs are incomplete, infer from CLAUDE.md and .maister/docs/

---

## Data Sources

### OpenClaw Codebase (`/home/bartek/Projects/openclaw/`)
- **Core documentation**: AGENTS.md (42KB, main architecture guide), VISION.md, SECURITY.md, CONTRIBUTING.md, docs.acp.md
- **Plugin system**: src/plugins/ (discovery, loader, registry, manifest, types), src/plugin-sdk/ (public contract, entry points, channel-contract, provider-entry)
- **Channels**: src/channels/ (core channel implementation, plugins/, transport/, web/)
- **ACP bridge**: src/acp/ (client, server, translator, session mapping, event mapper)
- **Skills**: skills/ (53 bundled skills), .agents/skills/ (agent-specific skills)
- **Extensions**: extensions/ (109 extensions -- providers, channels, tools)
- **Commands**: src/commands/ (agent, auth-choice, config, doctor, etc.)
- **Agents**: src/agents/ (ACP spawn, agent scope, auth health, payload policy)
- **Config**: src/config/ (configuration system)
- **Gateway docs**: docs/gateway/ (doctor, sandboxing, security, protocol, pairing)
- **Plugin docs**: docs/plugins/ (architecture, manifest, sdk-overview, sdk-entrypoints, sdk-runtime, sdk-channel-plugins, sdk-provider-plugins, building-plugins)
- **Concept docs**: docs/concepts/ (architecture, agent, session, context, memory)
- **Automation docs**: docs/automation/ (hooks, cron-jobs, taskflow, standing-orders, webhook)
- **Boundary guides**: src/plugin-sdk/AGENTS.md, src/channels/AGENTS.md, src/plugins/AGENTS.md, extensions/AGENTS.md

### Kukuvaia Documentation (`/home/bartek/Projects/kukuvaia/`)
- **Project docs**: .maister/docs/project/ (vision, roadmap, tech-stack, architecture, implementation-plan, work-log)
- **Standards**: .maister/docs/standards/ (global, security, backend, frontend, testing)
- **Architecture docs**: docs/architecture/ (auth-and-providers, memory-architecture, embabel-integration, security-review, tool-filtering, health-resilience, design-system)
- **Analysis docs**: docs/analyzes/agent-capabilities-gap-analysis.md
- **Plans**: docs/plan/ (kukuvaia-agent-implementation.md, kukuvaia-implementation-plan.md)
- **CLAUDE.md**: Project-level architecture decisions, module structure, design system

### External References (conceptual, no fetching needed)
- Spring AI documentation (advisor pattern, ChatClient, MCP)
- Java SPI (ServiceLoader) for plugin mechanisms
- Spring Plugin project (plugin discovery via Spring DI)
- OSGi patterns for module boundaries (if needed)

---

## Research Phases

### Phase 1: Broad Discovery
**Goal:** Map the full landscape of OpenClaw architectural elements and kukuvaia's current state.

**Actions:**
- Catalog all OpenClaw boundary guides (AGENTS.md files in each subsystem)
- Map the plugin system surface: manifest schema, capability model, registration API, loader pipeline
- Map the channel abstraction: types, adapters, plugin contract, routing
- Map the security model: trust boundaries, sandbox, approvals, operator model
- Map developer experience patterns: doctor, gates, multi-agent safety
- Map kukuvaia's current extension points and planned extensibility

**Output:** Comprehensive inventory of OpenClaw patterns with preliminary transferability assessment

### Phase 2: Targeted Reading
**Goal:** Deep-read the specific files that define each architectural pattern.

**Actions:**
- Read OpenClaw plugin SDK type definitions and entry point contracts
- Read OpenClaw channel plugin contract types and adapter interfaces
- Read OpenClaw security model docs (trust model, sandboxing modes, approval system)
- Read OpenClaw ACP bridge implementation (session mapping, event translation)
- Read OpenClaw doctor command implementation
- Read kukuvaia's implementation plan for planned features
- Read kukuvaia's security standards and architecture docs

**Output:** Detailed understanding of each pattern's internals and contracts

### Phase 3: Deep Dive -- Comparative Analysis
**Goal:** For each transferable pattern, determine the Spring/Java adaptation strategy.

**Actions:**
- Map OpenClaw `definePluginEntry` / capability registration to Spring `@Component` / SPI
- Map OpenClaw manifest-first validation to Spring configuration metadata
- Map OpenClaw channel abstraction to Spring messaging abstractions
- Map OpenClaw sandbox backends to Java ProcessBuilder / Docker API patterns
- Map OpenClaw hooks system to Spring AI Advisor chain
- Map OpenClaw doctor to Spring Boot Actuator health indicators
- Map OpenClaw multi-agent safety to kukuvaia's sub-agent system
- Map OpenClaw skill loading to kukuvaia's .kukuvaia/ extensibility

**Output:** Pattern-by-pattern mapping with adaptation strategies

### Phase 4: Verification and Synthesis
**Goal:** Validate findings and produce prioritized recommendations.

**Actions:**
- Cross-reference findings across all gatherer categories
- Identify patterns that do NOT transfer well and document why
- Assess effort/value for each transferable pattern
- Identify dependencies and implementation order
- Verify no conflicts with kukuvaia's existing architecture decisions

**Output:** Prioritized list of transferable patterns with implementation recommendations

---

## Gathering Strategy

### Instances: 5

| # | Category ID | Focus Area | Tools | Output Prefix |
|---|------------|------------|-------|---------------|
| 1 | openclaw-plugin-architecture | Plugin SDK boundaries, manifest system, capability model, loader pipeline, registry, hooks | Glob, Grep, Read | openclaw-plugin |
| 2 | openclaw-security-trust | Security model, operator trust boundaries, sandbox backends, approval system, security audit command | Glob, Grep, Read | openclaw-security |
| 3 | openclaw-channels-skills | Channel abstraction, messaging plugins, skills system, ClawHub marketplace, memory plugin slot, automation/hooks | Glob, Grep, Read | openclaw-channels |
| 4 | openclaw-devx-patterns | ACP bridge, doctor/diagnostic command, multi-agent safety rules, build/test gates, prompt cache stability | Glob, Grep, Read | openclaw-devx |
| 5 | kukuvaia-architecture | Current kukuvaia architecture, extension points, standards, planned features, implementation gaps | Glob, Grep, Read | kukuvaia-arch |

### Category Details

#### 1. openclaw-plugin-architecture
**Focus:** How OpenClaw structures its plugin system -- the manifest-first approach, SDK boundaries, capability registration model, loader pipeline, and runtime hooks.

**Key files to read:**
- `src/plugins/types.ts` -- core plugin type definitions
- `src/plugins/manifest.ts` -- manifest validation
- `src/plugins/loader.ts` -- plugin loading pipeline
- `src/plugins/registry.ts` -- plugin registry
- `src/plugins/discovery.ts` -- plugin discovery
- `src/plugin-sdk/plugin-entry.ts` -- definePluginEntry
- `src/plugin-sdk/core.ts` -- core SDK contract
- `src/plugin-sdk/provider-entry.ts` -- provider plugin entry
- `src/plugin-sdk/channel-contract.ts` -- channel plugin contract
- `src/plugins/hooks.ts` -- plugin hook system
- `src/plugins/AGENTS.md` -- boundary rules
- `src/plugin-sdk/AGENTS.md` -- SDK boundary rules
- `docs/plugins/architecture.md` -- plugin internals doc
- `docs/plugins/manifest.md` -- manifest spec
- `docs/plugins/sdk-overview.md` -- SDK overview
- `docs/plugins/sdk-entrypoints.md` -- entry point reference
- `extensions/AGENTS.md` -- extension boundary rules

**Questions to answer:**
- What is the full plugin lifecycle (discover -> validate manifest -> load -> register -> activate)?
- How does the capability model work (plain-capability, hybrid-capability, hook-only)?
- How are plugin boundaries enforced (import restrictions, SDK-only surface)?
- What is the hooks system and how does it compose with plugin capabilities?
- How does config schema validation work at the manifest level vs runtime?

#### 2. openclaw-security-trust
**Focus:** How OpenClaw models trust boundaries, operator security, sandbox execution, and approval workflows.

**Key files to read:**
- `SECURITY.md` -- security policy and trust model
- `docs/gateway/security/index.md` -- security guidance
- `docs/gateway/sandboxing.md` -- sandbox architecture
- `docs/gateway/sandbox-vs-tool-policy-vs-elevated.md` -- security policy comparison
- `src/plugin-sdk/allow-from.ts` -- allowlist implementation
- `src/channels/allow-from.ts` -- channel allowlists
- `src/acp/approval-classifier.ts` -- ACP approval classification
- `src/plugin-sdk/approval-*.ts` -- approval system
- `docs/cli/security.md` -- CLI security commands
- `docs/cli/approvals.md` -- approval docs
- `docs/cli/sandbox.md` -- sandbox CLI docs
- `docs/gateway/secrets.md` -- secrets management

**Questions to answer:**
- What is the operator trust model and how does it apply to single-user vs multi-user?
- How does the sandbox system work (modes, scopes, backends)?
- What is the approval system and how does it gate tool execution?
- How are allowlists structured (channel, sender, tool)?
- What is the security audit command and what does it check?

#### 3. openclaw-channels-skills
**Focus:** How OpenClaw abstracts messaging channels, routes messages, manages skills, and handles the ClawHub marketplace.

**Key files to read:**
- `src/channels/plugins/types.plugin.ts` -- channel plugin types
- `src/channels/plugins/types.core.ts` -- core channel types
- `src/channels/plugins/types.adapters.ts` -- channel adapter types
- `src/channels/registry.ts` -- channel registry
- `src/channels/session.ts` -- channel sessions
- `src/channels/AGENTS.md` -- channel boundary guide
- `src/plugins/clawhub.ts` -- ClawHub marketplace
- `src/plugins/memory-state.ts` -- memory plugin slot
- `docs/channels/index.md` -- channels overview
- `docs/channels/channel-routing.md` -- channel routing
- `docs/channels/pairing.md` -- device pairing
- `docs/cli/skills.md` -- skills CLI
- `docs/automation/hooks.md` -- automation hooks
- Skills directory structure (e.g., skills/healthcheck/, skills/github/)
- `VISION.md` -- skills and memory vision

**Questions to answer:**
- How is the channel abstraction layered (transport -> adapter -> plugin)?
- How does channel routing work (inbound -> session -> agent)?
- What is the skills model (directory structure, SKILL.md, loading)?
- How does ClawHub work (install, update, registry)?
- How does the memory plugin slot work (exclusive, one-at-a-time)?

#### 4. openclaw-devx-patterns
**Focus:** Developer experience patterns -- ACP bridge for IDE integration, doctor diagnostic command, multi-agent safety rules, build/test gates, prompt cache stability.

**Key files to read:**
- `src/acp/client.ts` -- ACP client implementation
- `src/acp/server.ts` -- ACP server
- `src/acp/translator.ts` -- ACP-Gateway translator
- `src/acp/session.ts` -- ACP session mapping
- `docs.acp.md` -- ACP bridge documentation
- `docs/cli/doctor.md` -- doctor CLI docs
- `docs/gateway/doctor.md` -- doctor gateway docs
- `docs/diagnostics/` -- diagnostic docs
- AGENTS.md multi-agent safety section (lines 305-316)
- AGENTS.md build/test gate terminology (lines 140-163)
- AGENTS.md prompt cache stability (lines 166-172)
- `docs/cli/acp.md` -- ACP CLI docs
- `docs/concepts/agent.md` -- agent concepts

**Questions to answer:**
- How does the ACP bridge map IDE sessions to Gateway sessions?
- What does `openclaw doctor` check and repair?
- What are the multi-agent safety rules and how are they enforced?
- What are the build/test gate levels (dev, landing, CI)?
- How does prompt cache stability affect architecture?

#### 5. kukuvaia-architecture
**Focus:** Current kukuvaia architecture, existing extension points, standards, and planned features to identify gaps that OpenClaw patterns could fill.

**Key files to read:**
- `.maister/docs/project/architecture.md` -- system architecture
- `.maister/docs/project/implementation-plan.md` -- implementation plan with remaining TODO
- `.maister/docs/project/roadmap.md` -- development priorities
- `.maister/docs/project/tech-stack.md` -- technology choices
- `.maister/docs/project/vision.md` -- project vision
- `docs/architecture/auth-and-providers.md` -- auth and provider routing
- `docs/architecture/memory-architecture.md` -- memory system
- `docs/architecture/embabel-integration.md` -- Embabel/GOAP integration
- `docs/architecture/security-review.md` -- security review
- `docs/architecture/tool-filtering.md` -- tool filtering
- `docs/architecture/health-resilience.md` -- health and resilience
- `docs/analyzes/agent-capabilities-gap-analysis.md` -- gap analysis
- `.maister/docs/standards/security/` -- all security standards
- `.maister/docs/standards/backend/architecture.md` -- backend architecture standards
- `CLAUDE.md` -- project architecture overview

**Questions to answer:**
- What extension points does kukuvaia currently have (MCP tools, personas, advisors)?
- What is planned for the .kukuvaia/ user extensibility directory?
- What is kukuvaia's current security model vs what OpenClaw offers?
- What gaps exist in kukuvaia that OpenClaw patterns could address?
- How do Spring AI advisors relate to OpenClaw's hooks system?

### Rationale
The 5-category split aligns with the natural boundaries in both codebases. OpenClaw categories (1-4) are split by subsystem rather than by file type because the research question is about transferable **architectural patterns**, not about cataloging files. The kukuvaia category (5) provides the target architecture context needed for the comparative analysis. Each gatherer can work independently because the categories have minimal overlap -- plugin architecture is distinct from security model, which is distinct from channels/skills, which is distinct from devx patterns.

---

## Success Criteria

1. **Comprehensive pattern inventory**: Every major OpenClaw architectural element is identified and classified (transferable / partially transferable / not applicable)
2. **Spring/Java mapping**: Each transferable pattern has a concrete adaptation strategy for Java 21+ / Spring Boot 3.x / Spring AI 1.x
3. **Prioritized recommendations**: Patterns are ranked by value/effort with clear implementation order
4. **Non-transferable patterns documented**: Patterns that do not transfer well are explained (with reasons)
5. **No architecture conflicts**: Recommendations do not break kukuvaia's existing decisions (advisor chains, MCP tools, module structure)
6. **Actionable next steps**: Each recommendation includes enough detail to create implementation tasks

---

## Expected Outputs

1. **Findings files**: One per gatherer category in `analysis/findings/`
   - `openclaw-plugin-findings.md` -- Plugin architecture analysis
   - `openclaw-security-findings.md` -- Security model analysis
   - `openclaw-channels-findings.md` -- Channels and skills analysis
   - `openclaw-devx-findings.md` -- DevX patterns analysis
   - `kukuvaia-arch-findings.md` -- Current architecture assessment

2. **Research report**: Synthesized findings with:
   - Pattern-by-pattern transferability assessment
   - Spring/Java adaptation strategies for transferable patterns
   - Prioritized implementation roadmap
   - Patterns that do not transfer (with rationale)
   - Gap analysis (what kukuvaia needs that OpenClaw has solved)

3. **Recommendations**: Actionable list ordered by priority, including:
   - What to implement
   - How to adapt it to Spring/Java
   - Effort estimate (S/M/L)
   - Dependencies on other patterns

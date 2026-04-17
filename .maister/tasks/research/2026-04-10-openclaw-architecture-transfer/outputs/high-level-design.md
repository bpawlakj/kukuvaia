# High-Level Design: OpenClaw Architecture Transfer to Kukuvaia

## Design Overview

**Business context**: Kukuvaia is an early-stage, open-source conversational AI agent platform moving toward production readiness. Its extensibility surface (9 extension points across tools, rules, skills, commands, personas, specialists, advisors, @Tool methods, and Embabel agents) lacks the unifying contracts and safety infrastructure found in mature agent platforms like OpenClaw. Hardening security and enriching extensibility now reduces risk before the user base grows.

**Chosen approach**: Transfer OpenClaw's **security primitives** and **extensibility contracts** -- not its large-scale abstractions -- into kukuvaia's existing **Spring AI advisor chain** architecture. Three convergence decisions drive the design: (1) **Skills-First Pattern Extraction** for extension unification, enriching skills before extracting shared contracts; (2) **Incremental Security Advisors** under a unified `kukuvaia.security.*` config namespace for tool policy groups, content wrapping, and dangerous config naming; (3) **Hybrid Static Groups + Annotations** for tool safety, starting with configuration-only groups in Phase A and adding `@ToolSafety` annotations in Phase C.

**Key decisions:**
- Enrich skills system first as a proving ground for extension metadata patterns, deferring shared contracts until patterns are validated (YAGNI)
- Implement security additions as independent advisors sharing a config namespace, not a unified policy engine
- Tool safety uses two layers: configuration groups (deny-always-wins) for policy enforcement, annotations for classification metadata
- All new components integrate into the existing advisor chain -- no new architectural mechanisms introduced
- Phase A (security) and Phase B (experience) are independent and can overlap; Phase C depends on Phase A

---

## Architecture

### System Context (C4 Level 1)

This diagram shows kukuvaia as a system in its environment. New elements from the OpenClaw transfer are marked with `[NEW]`.

```
                                    +-------------------+
                                    |   LLM Providers   |
                                    | (Copilot/SmartGate)|
                                    +--------+----------+
                                             |
                                             | OpenAI API (HTTP)
                                             |
+-------------+     HTTP/SSE      +----------+-----------+     JDBC      +--------------+
|             |  <--------------> |                      | <-----------> |  PostgreSQL   |
| kukuvaia-cli|                   |   kukuvaia-engine    |              | (agent +      |
| (Go TUI)   |                   |                      |              |  data schemas) |
+-------------+                   |  +-----------------+ |              +--------------+
                                  |  | Advisor Chain   | |
                                  |  |  +ToolPolicy    | | [NEW]
                                  |  |  +ContentWrap   | | [NEW]
                                  |  +-----------------+ |
                                  |                      |     MCP      +--------------+
                                  |  +-----------------+ | <----------> | External MCP |
                                  |  | Skills System   | |              | Servers      |
                                  |  |  +Precedence    | | [NEW]        +--------------+
                                  |  |  +Eligibility   | | [NEW]
                                  |  +-----------------+ |
                                  |                      |
                                  |  +-----------------+ |
                                  |  | Doctor Command  | | [NEW]
                                  |  +-----------------+ |
                                  +----------------------+
```

**Key interactions:**
- CLI communicates with engine via HTTP/SSE (unchanged)
- Engine communicates with LLM providers via OpenAI-compatible API (unchanged)
- Advisor chain gains two new advisors: `ToolPolicyAdvisor` and `ExternalContentWrappingAdvisor`
- Skills system gains multi-source discovery with precedence ordering
- Doctor command aggregates health indicators from all subsystems

### Container Overview (C4 Level 2)

This diagram shows kukuvaia-engine's internal containers (Gradle modules) and where new components land.

```
+------------------------------------------------------------------+
|  kukuvaia-engine                                                  |
|                                                                   |
|  +------------------------------------------------------------+  |
|  | kukuvaia-app (Spring Boot entry point)                      |  |
|  |  application.yml with kukuvaia.security.* namespace [NEW]   |  |
|  +------------------------------------------------------------+  |
|          |              |              |              |            |
|          v              v              v              v            |
|  +-------------+ +-------------+ +-------------+ +-------------+ |
|  | kukuvaia-   | | kukuvaia-   | | kukuvaia-   | | kukuvaia-   | |
|  | core        | | agents      | | memory      | | app         | |
|  | (Java)      | | (Kotlin)    | | (Java)      | | (Boot)      | |
|  |             | |             | |             | |             | |
|  | Security:   | | Embabel     | | SmartMemory | | Config:     | |
|  |  ToolPolicy | | GOAP agents | | Advisor     | |  Security   | |
|  |  Service    | |             | | pgvector    | |  Properties | |
|  |  [NEW]      | |             | |             | |  [NEW]      | |
|  |             | |             | |             | |             | |
|  | Advisors:   | |             | |             | |             | |
|  |  ToolPolicy | |             | |             | |             | |
|  |  Advisor    | |             | |             | |             | |
|  |  [NEW]      | |             | |             | |             | |
|  |  Content    | |             | |             | |             | |
|  |  Wrapping   | |             | |             | |             | |
|  |  Advisor    | |             | |             | |             | |
|  |  [NEW]      | |             | |             | |             | |
|  |             | |             | |             | |             | |
|  | Skills:     | |             | |             | |             | |
|  |  Precedence | |             | |             | |             | |
|  |  Resolver   | |             | |             | |             | |
|  |  [NEW]      | |             | |             | |             | |
|  |  Eligibility| |             | |             | |             | |
|  |  Checker    | |             | |             | |             | |
|  |  [NEW]      | |             | |             | |             | |
|  |             | |             | |             | |             | |
|  | Commands:   | |             | |             | |             | |
|  |  Doctor     | |             | |             | |             | |
|  |  Command    | |             | |             | |             | |
|  |  [NEW]      | |             | |             | |             | |
|  +-------------+ +-------------+ +-------------+ +-------------+ |
+------------------------------------------------------------------+
```

**Module responsibilities:**
- **kukuvaia-core**: All new domain logic -- tool policy service, content wrapping advisor, skills enrichment, doctor command. This is the primary target for OpenClaw transfer.
- **kukuvaia-agents**: Unchanged. Embabel GOAP agents are orthogonal to this transfer.
- **kukuvaia-memory**: Unchanged. Memory architecture is not affected.
- **kukuvaia-app**: Configuration additions only -- `kukuvaia.security.*` namespace properties.

---

## Key Components

| Component | Purpose | Responsibilities | Key Interfaces | Dependencies |
|-----------|---------|-----------------|----------------|--------------|
| **ToolPolicyService** | Resolves tool access policies using groups and deny-always-wins semantics | - Parse tool group definitions from config<br>- Resolve `group:*` references to tool lists<br>- Evaluate allow/deny with deny-always-wins<br>- Per-persona policy resolution | `isToolAllowed(toolName, persona): boolean`<br>`resolveGroups(groupRefs): Set<String>` | `ToolPolicyProperties` (config) |
| **ToolPolicyAdvisor** | Advisor chain participant that filters tool calls based on policy | - Intercept tool call requests<br>- Delegate to ToolPolicyService<br>- Block denied tools before execution | Implements Spring AI `Advisor` | `ToolPolicyService`, existing `ToolFilteringAdvisor` (may enhance or replace) |
| **ExternalContentWrappingAdvisor** | Wraps tool results with boundary markers and normalizes content | - Generate UUID-based boundary markers<br>- Apply NFKC Unicode normalization<br>- Strip zero-width characters<br>- Label content with source tool name | Implements Spring AI `Advisor` | None (standalone advisor) |
| **DangerousConfigWarner** | Logs warnings at startup for any `dangerously-*` config flags enabled | - Scan `kukuvaia.security.*` for dangerous flags<br>- Emit WARN-level log for each enabled flag | `@PostConstruct` in `SecurityConfig` | `SecurityProperties` |
| **SkillPrecedenceResolver** | Discovers skills from multiple sources with precedence ordering | - Scan classpath `/skills/` (built-in)<br>- Scan `~/.kukuvaia/skills/` (user global)<br>- Scan `.kukuvaia/skills/` (project, highest)<br>- Apply name-based override semantics | `resolveSkills(): List<SkillSpec>` | File system, classpath |
| **SkillEligibilityChecker** | Validates skill requirements at runtime | - Check binary availability (`which` / `where`)<br>- Check environment variable presence<br>- Check MCP server connectivity | `isEligible(SkillSpec): EligibilityResult` | `ProcessBuilder`, `System.getenv()` |
| **DoctorService** | Aggregates health indicators for diagnostic output | - Collect all `HealthIndicator` beans<br>- Execute health checks<br>- Format results as `TableBlock` | `runDiagnostics(): List<DiagnosticResult>` | Spring Boot `HealthIndicator` beans |
| **DoctorCommand** | Tier 1 slash command for `/doctor` | - Register with `CommandRouter`<br>- Invoke `DoctorService`<br>- Return structured output | Implements slash command interface | `DoctorService`, `CommandRouter` |

---

## Data Flow

### Tool Call Flow (with new security layers)

```
User Message
    |
    v
[ChatClient receives message]
    |
    v
[ProviderAuditLogAdvisor] -- logs provider call
    |
    v
[ToolPolicyAdvisor] [NEW] -- evaluates tool availability per persona policy
    |                         deny-always-wins: denied tools removed from
    |                         available tool list before LLM sees them
    v
[ToolResultSanitizingAdvisor] -- existing sanitization
    |
    v
[ExternalContentWrappingAdvisor] [NEW] -- wraps tool results with boundary
    |                                      markers, NFKC normalization,
    |                                      zero-width stripping, source labels
    v
[SmartMemoryAdvisor] -- memory retrieval
    |
    v
[MessageChatMemoryAdvisor] -- conversation history
    |
    v
[ToolCallAdvisor] -- LLM decides which tools to call
    |
    v
[ToolHookDispatcher] -- executes tool calls
    |
    v
Tool results flow back through advisor chain
(ContentWrappingAdvisor processes results on response path)
    |
    v
Response streamed to CLI via SSE
```

**Key flow changes:**
1. `ToolPolicyAdvisor` filters available tools BEFORE the LLM sees them -- denied tools are invisible, not just blocked
2. `ExternalContentWrappingAdvisor` processes tool results AFTER execution but BEFORE the LLM processes them
3. Both advisors are stateless and add microsecond-level overhead

### Skills Discovery Flow (new)

```
Application Startup / Reload Trigger
    |
    v
[SkillPrecedenceResolver]
    |
    +-- Scan classpath:/skills/         (priority: LOW, built-in)
    |       |
    |       v
    +-- Scan ~/.kukuvaia/skills/        (priority: MEDIUM, user global)
    |       |
    |       v
    +-- Scan .kukuvaia/skills/          (priority: HIGH, project)
    |
    v
[Merge by name: higher priority overrides lower]
    |
    v
[SkillEligibilityChecker]
    |
    +-- For each skill:
    |     Check requires.bins (which/where)
    |     Check requires.env (System.getenv)
    |     Check requires.mcp (server connectivity)
    |
    v
[Eligible skills registered in SkillRegistry]
    |
    v
[Summaries injected into system prompt]
```

### Doctor Command Flow (new)

```
User types /doctor
    |
    v
[CommandRouter] -- Tier 1 (deterministic, no LLM)
    |
    v
[DoctorCommand]
    |
    v
[DoctorService.runDiagnostics()]
    |
    +-- LlmProviderHealthIndicator     -- ping active provider
    +-- DatabaseHealthIndicator        -- PG connectivity + schema version
    +-- MemoryHealthIndicator          -- pgvector extension, dimensions
    +-- ToolRegistryHealthIndicator    -- MCP tool availability
    +-- SecurityAuditHealthIndicator   -- dangerous flags, file perms
    +-- SessionIntegrityHealthIndicator -- orphaned sessions
    |
    v
[Format as TableBlock]
    |
    v
[Return to CLI for rendering]
```

---

## Integration Points

### Integration with Existing Components

| New Component | Integrates With | Integration Type | Notes |
|---------------|----------------|-----------------|-------|
| `ToolPolicyAdvisor` | `ChatClientConfig` | Advisor chain registration | Inserted at precedence AFTER `ProviderAuditLogAdvisor`, BEFORE `ToolResultSanitizingAdvisor` |
| `ToolPolicyAdvisor` | `ToolFilteringAdvisor` | May enhance or wrap | Existing tool filtering logic moves into or delegates to `ToolPolicyService` |
| `ToolPolicyAdvisor` | `PersonaService` | Reads persona config | Persona YAML gains `tools.allow` and `tools.deny` fields |
| `ExternalContentWrappingAdvisor` | `ToolResultSanitizingAdvisor` | Companion or enhancement | Could enhance existing advisor or run as separate advisor after it |
| `ExternalContentWrappingAdvisor` | `ChatClientConfig` | Advisor chain registration | Inserted AFTER `ToolResultSanitizingAdvisor`, BEFORE `SmartMemoryAdvisor` |
| `SkillPrecedenceResolver` | `SkillsLoader` | Replaces discovery logic | Existing single-source discovery replaced with multi-source precedence |
| `SkillEligibilityChecker` | `SkillRegistry` | Filters registrations | Only eligible skills are registered; ineligible skills logged at DEBUG |
| `DoctorCommand` | `CommandRouter` | Tier 1 command registration | Registered as deterministic command (no LLM) |
| `DoctorService` | Spring Boot Actuator | Collects `HealthIndicator` beans | Uses `@Autowired List<HealthIndicator>` for discovery |
| `SecurityProperties` | `application.yml` | `@ConfigurationProperties` | Binds `kukuvaia.security.*` namespace |
| `DangerousConfigWarner` | `SecurityConfig` | `@PostConstruct` | Iterates dangerous flags, emits WARN log |

### Updated Advisor Chain Order

Current:
```
ProviderAuditLog -> ToolResultSanitizing -> SmartMemory -> MessageChatMemory -> ToolCallAdvisor -> ToolHookDispatcher
```

After Phase A:
```
ProviderAuditLog -> ToolPolicy [NEW] -> ToolResultSanitizing -> ExternalContentWrapping [NEW] -> SmartMemory -> MessageChatMemory -> ToolCallAdvisor -> ToolHookDispatcher
```

Rationale for positions:
- `ToolPolicyAdvisor` before `ToolResultSanitizing` because it filters which tools are available -- this must happen before any tool execution
- `ExternalContentWrappingAdvisor` after `ToolResultSanitizing` because it adds boundary markers to already-sanitized content -- sanitization first, then wrapping

---

## Phase A Detailed Design: Security Hardening (2-3 days)

### A1: Tool Policy Groups + Deny-Always-Wins

**Configuration** (`application.yml`):
```yaml
kukuvaia:
  security:
    tool-policies:
      groups:
        readonly: [search_items, get_classifications, get_outline_raw, get_sections]
        mutating: [create_item, update_item, delete_item]
        dangerous: [shell_exec, process_run]
      default-policy:
        deny: [group:dangerous]
      personas:
        admin:
          allow: [group:dangerous]
          deny: []   # deny-always-wins: if both allow and deny contain a tool, deny wins
```

**New classes** (in `ai.kukuvaia.security`):
- `ToolPolicyProperties` -- `@ConfigurationProperties(prefix = "kukuvaia.security.tool-policies")`, records for groups, default-policy, per-persona policies
- `ToolPolicyService` -- resolves group references, evaluates allow/deny with deny-always-wins, returns filtered tool set

**Modified classes**:
- `ToolFilteringAdvisor` -- delegates to `ToolPolicyService` for policy evaluation instead of (or in addition to) current name-based filtering

**Tests**: Policy evaluation with group resolution, deny-always-wins precedence, persona override, default policy fallback.

### A2: External Content Wrapping

**New class** (in `ai.kukuvaia.security`):
- `ExternalContentWrappingAdvisor` -- Spring AI `Advisor` implementation that wraps tool results with UUID-based boundary markers, applies NFKC normalization, strips zero-width characters, and labels with source tool name

**Key method signature**:
```
wrapExternalContent(content: String, source: String) -> String
```

Produces:
```
<tool-result source="search_items" boundary="a1b2c3d4e5f6g7h8">
[normalized content with zero-width chars stripped]
</tool-result boundary="a1b2c3d4e5f6g7h8">
```

**Tests**: Boundary marker uniqueness, NFKC normalization coverage, zero-width stripping, round-trip content integrity.

### A3: Dangerous Config Naming

**Convention**: Any `kukuvaia.security.*` property that weakens security uses `dangerously-` prefix.

**Initial dangerous flags**:
- `kukuvaia.security.dangerously-disable-auth` (default: false)
- `kukuvaia.security.dangerously-allow-raw-shell` (default: false)
- `kukuvaia.security.dangerously-skip-content-wrapping` (default: false)

**Implementation**: `@PostConstruct` method in `SecurityConfig` iterates all `kukuvaia.security.dangerously-*` properties. If any are `true`, emit `log.warn("SECURITY: dangerous flag enabled: {}", flagName)`.

### A4: Trust Model Documentation

**File**: `SECURITY.md` at repository root.

**Content**: Trust model (single-operator per instance), five trust boundaries with controls, out-of-scope definitions, security contact.

---

## Phase B Detailed Design: Developer Experience (3-4 days)

### B1: Prompt Cache Stability

**Changes to `AgentService`**:
1. Sort tool descriptions alphabetically before injection into system prompt
2. Insert `<!-- KUKUVAIA_CACHE_BOUNDARY -->` marker between stable prefix (persona definition, tool descriptions, rules) and dynamic suffix (session context, memory, recent messages)
3. Normalize whitespace in stable section (collapse multiple spaces/newlines)

**Impact**: LLM providers that support prompt caching (Anthropic, OpenAI) can cache the stable prefix, reducing latency and cost for repeated interactions within the same persona/tool configuration.

### B2: Skills Enrichment

**New classes** (in `ai.kukuvaia.skills`):
- `SkillPrecedenceResolver` -- scans three sources (classpath, user global, project) and merges by name with project-wins precedence
- `SkillEligibilityChecker` -- validates `requires` field (bins, env, mcp) at runtime

**Modified classes**:
- `SkillsLoader` -- delegates discovery to `SkillPrecedenceResolver`, filters through `SkillEligibilityChecker`
- `SkillSpec` -- gains optional `requires` field (record with `bins: List<String>`, `env: List<String>`, `mcp: List<String>`)

**Backward compatibility**: All new fields are optional. Existing skills without `requires` field are always eligible. Single-source skills in `.kukuvaia/skills/` continue to work unchanged.

### B3: Doctor Command

**New classes** (in `ai.kukuvaia.commands`):
- `DoctorCommand` -- Tier 1 slash command, invokes `DoctorService`

**New classes** (in `ai.kukuvaia.diagnostics`):
- `DoctorService` -- collects `HealthIndicator` beans, runs checks, formats output
- `LlmProviderHealthIndicator` -- pings active LLM provider
- `DatabaseHealthIndicator` -- checks PG connectivity and schema version
- `MemoryHealthIndicator` -- verifies pgvector extension and embedding configuration
- `ToolRegistryHealthIndicator` -- checks MCP tool availability
- `SecurityAuditHealthIndicator` -- reports dangerous flags and file permissions

**Output format**: `TableBlock` with columns: Check, Status (UP/DOWN/WARN), Details.

---

## Phase C Sketch: Architecture Enrichment (4-5 days)

### C1: Tool Safety Annotations

Add `@ToolSafety(level = SafetyLevel.MUTATING)` annotation for `@McpTool` methods. `ToolSafetyClassifier` service resolves safety level from annotation (in-process tools) or configuration (external MCP tools). Groups can reference safety levels: `deny: [safety:exec_capable]`.

**Enum**: `SafetyLevel { READ_ONLY, MUTATING, EXEC_CAPABLE, CONTROL_PLANE }`

### C2: Session Key Grammar

`SessionKeyBuilder` utility producing structured keys: `kukuvaia:<persona>:<conversationId>[:sub:<subAgentId>][:daemon:<daemonId>]`. Backward-compatible with existing opaque keys.

### C3: Advisor Chain Enrichment

Investigate adding `isTerminal()` and `FailMode` (OPEN/CLOSED) to advisor base. Requires spike into Spring AI advisor chain internals. May need custom `AdvisorChainRunner`.

---

## Design Decisions

| ADR | Title | Decision | Rationale |
|-----|-------|----------|-----------|
| [ADR-001](decision-log.md#adr-001-extensionplugin-unification-strategy) | Extension/Plugin Unification Strategy | Skills-First Pattern Extraction | YAGNI -- validate patterns before committing to shared abstractions |
| [ADR-002](decision-log.md#adr-002-security-hardening-approach) | Security Hardening Approach | Incremental Advisors + Config Namespace | Independent, testable, revertible changes under unified config surface |
| [ADR-003](decision-log.md#adr-003-tool-safety-and-approval-model) | Tool Safety and Approval Model | Hybrid Static Groups + Annotations (phased) | Groups for immediate deny-always-wins; annotations for richer classification later |

---

## Concrete Examples

### Example 1: Tool Denied by Policy

**Given** a persona "analyst" with policy `deny: [group:dangerous]`, and "dangerous" group contains `[shell_exec, process_run]`
**When** the LLM attempts to call `shell_exec` during a conversation with the "analyst" persona
**Then** `ToolPolicyAdvisor` removes `shell_exec` from the available tool list before the LLM sees it; the LLM never knows the tool exists and uses alternative approaches. The denial is logged at INFO level with persona name and tool name.

### Example 2: Malicious Content in Tool Result

**Given** a tool `search_items` returns content containing `</tool-result>` injection attempt and zero-width Unicode characters
**When** `ExternalContentWrappingAdvisor` processes the tool result
**Then** the result is wrapped with a UUID-based boundary marker, NFKC-normalized (homoglyphs folded), zero-width characters stripped, and labeled with source `search_items`. The LLM sees a cleanly bounded, normalized result. The injection attempt is neutralized because the boundary marker is crypto-random and cannot be predicted.

### Example 3: Project Skill Overrides Built-in

**Given** a built-in skill `github-pr` at `classpath:/skills/github-pr/SKILL.md` and a project skill `github-pr` at `.kukuvaia/skills/github-pr/SKILL.md` with customized instructions
**When** `SkillPrecedenceResolver` discovers skills at startup
**Then** the project version overrides the built-in version (project > user > built-in precedence). `SkillEligibilityChecker` verifies `requires.bins: [gh]` by checking `which gh`. If `gh` is not found, the skill is marked ineligible, logged at DEBUG, and not registered. The `/doctor` command reports this as a WARN diagnostic.

---

## Out of Scope

The following are explicitly NOT addressed by this design:

1. **Unified plugin SDK / `kukuvaia-plugin-api` module** -- deferred until skills patterns are proven and extracted (Phase D). See ADR-001.
2. **Channel abstraction** -- kukuvaia has one transport (HTTP/SSE). Multi-channel routing is premature.
3. **Marketplace / plugin distribution** -- requires plugin SDK and user community that do not exist yet.
4. **ACP/IDE bridge** -- IDE integration is not on kukuvaia's roadmap.
5. **Exec approval workflow** -- requires shell/file tools that do not exist yet. Deferred to document agent phase.
6. **Sandbox backends (Docker/SSH)** -- same prerequisite as exec approval.
7. **Formal verification (TLA+)** -- integration tests are the pragmatic equivalent.
8. **Exclusive slot system** -- only memory needs it; `@ConditionalOnMissingBean` suffices without formal abstraction.
9. **Implementation file paths and package structures** -- that detail belongs in the project-specific specification, not this architecture design.

**Deferred stretch ideas** (tracked from solution exploration):
- Advisor chain terminal semantics (`isTerminal()` + `FailMode`) -- Phase C investigation spike
- Session key grammar -- Phase C, small effort
- Shared extension metadata contract -- after skills patterns proven

---

## Success Criteria

1. **Tool policy deny-always-wins is enforced**: When a tool is in both `allow` and `deny` for a persona, it is denied. Verified by unit test.
2. **External content wrapping neutralizes injection**: Tool results containing boundary-marker injection attempts are safely wrapped. Verified by unit test with adversarial inputs.
3. **Dangerous config flags emit warnings**: Any `dangerously-*` flag set to `true` produces a WARN log at startup. Verified by integration test.
4. **Skills discover from three sources with correct precedence**: Project skills override user skills override built-in skills. Verified by unit test with multi-source fixture.
5. **Doctor command reports system health**: `/doctor` returns health status for all registered indicators. Verified by integration test.
6. **Prompt cache boundary is stable**: System prompt static section produces byte-identical output for the same persona and tool configuration. Verified by deterministic output test.

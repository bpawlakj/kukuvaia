# Findings: OpenClaw Channels & Skills

## Summary

OpenClaw implements a layered channel abstraction where messaging platforms are encapsulated as `ChannelPlugin` objects with ~25 optional adapter surfaces (config, security, pairing, outbound, threading, messaging, actions, etc.). Core owns a shared `message` tool and dispatch logic; channel plugins own platform-specific behavior through typed adapter contracts. Skills are markdown-first (`SKILL.md` frontmatter files) organized in directories, discovered at runtime from bundled/workspace/managed paths, injected into the system prompt as XML, and distributed via ClawHub marketplace. Memory is a special exclusive plugin slot where only one memory plugin can be active at a time, registered via `registerMemoryCapability()`.

## Key Findings

### Finding 1: ChannelPlugin Adapter Surface -- Composition over Inheritance

**Source**: `src/channels/plugins/types.plugin.ts:84-127`
**Evidence**: The `ChannelPlugin` type is a flat object with ~25 optional adapter fields (config, setup, pairing, security, outbound, streaming, threading, messaging, actions, gateway, auth, lifecycle, doctor, etc.). Each adapter is a typed object of functions -- no class hierarchies. A channel plugin implements only the adapters it needs.
**Relevance**: Maps directly to Java interfaces with default methods or Spring `@Component` with optional `@ConditionalOnProperty` sub-beans. Channels as Spring beans implementing selected adapter interfaces, registered in a `ChannelRegistry`.

### Finding 2: Core-Owns-Tool / Plugin-Owns-Dispatch

**Source**: `docs/plugins/sdk-channel-plugins.md:22-33`, `docs/plugins/architecture.md:155-200`
**Evidence**: Core owns one shared `message` tool, prompt wiring, session/thread bookkeeping, execution dispatch. Channel plugins own scoped action discovery via `describeMessageTool()`, channel-specific schema fragments, session conversation grammar, and final action execution.
**Relevance**: Instead of each channel providing its own MCP tools, kukuvaia could have a single `MessageService` that delegates to channel-specific adapters.

### Finding 3: Channel Registration via Plugin Registry

**Source**: `src/channels/registry.ts:1-100`
**Evidence**: Channel registry delegates to plugin registry at runtime. Channels discovered through plugin system, not hardcoded.
**Relevance**: Maps to Spring DI: `@Component` beans implementing `Channel` interface, auto-discovered and collected in `ChannelRegistry` via `@Autowired List<Channel>`.

### Finding 4: Deterministic Routing -- Agent Selection by Binding Rules

**Source**: `docs/channels/channel-routing.md:59-73`, `src/routing/resolve-route.ts:1-60`
**Evidence**: Routing follows priority cascade: exact peer match → parent peer → guild+roles → guild → team → account → channel → default agent. Output: `ResolvedAgentRoute` with agentId, sessionKey, matchedBy.
**Relevance**: Proven model for multi-agent/multi-channel scenarios. Priority cascade implementable as ordered `RoutingRule` matchers.

### Finding 5: Session Key Grammar -- Channel-Aware Session Isolation

**Source**: `docs/channels/channel-routing.md:22-39`
**Evidence**: Session keys encode channel, chat type, threading: `agent:<agentId>:<channel>:group:<id>`, threads append `:thread:<threadId>`. Channels can customize via `messaging.resolveSessionConversation()`.
**Relevance**: Shows how to systematically encode channel context into session identifiers for proper isolation in kukuvaia's `JdbcChatMemoryRepository`.

### Finding 6: Channel Capabilities as Static Flags

**Source**: `src/channels/plugins/types.core.ts:251-265`
**Evidence**: `ChannelCapabilities` type with boolean flags: polls, reactions, edit, unsend, reply, threads, media, blockStreaming. Declared at registration time.
**Relevance**: Capability flags on channel interface allow core to adapt behavior without per-channel conditionals.

### Finding 7: Channel Security -- DM Policy and Allowlists

**Source**: `docs/channels/pairing.md:1-60`
**Evidence**: Per-channel DM policies: `allowlist` (must be on list), `pairing` (unknown senders get code, wait for approval), `open`. Pairing codes: 8 chars, expire 1h, max 3 pending.
**Relevance**: Per-channel DM security policies are needed if kukuvaia adds messaging channels.

### Finding 8: Skills System -- Markdown-First with Frontmatter

**Source**: `skills/healthcheck/SKILL.md`, `src/agents/skills/skill-contract.ts:1-64`, `src/agents/skills/local-loader.ts:38-85`
**Evidence**: Each skill is a directory with `SKILL.md` containing YAML frontmatter (name, description, requires, install). Loader parses frontmatter, injects summaries into system prompt as XML. Model reads full SKILL.md on demand.
**Relevance**: This is what kukuvaia plans for `.kukuvaia/` extensibility. Pattern: discover → parse metadata → inject summaries → lazy load. Maps to `.kukuvaia/skills/` with `SkillDiscoveryAdvisor`.

### Finding 9: Skill Discovery from Multiple Sources with Precedence

**Source**: `src/agents/skills/workspace.ts:1-80`, `src/agents/skills/local-loader.ts:87-135`
**Evidence**: Skills from: bundled < plugin < managed (`~/.openclaw/skills/`) < workspace (`<workspace>/skills/`). Workspace overrides managed of same name. Symlink rejection for security.
**Relevance**: Kukuvaia: built-in (classpath) < user (`~/.kukuvaia/skills/`) < project (`.kukuvaia/skills/`). Maps to Spring `ResourceLoader`.

### Finding 10: ClawHub Marketplace -- Distribution

**Source**: `src/plugins/clawhub.ts:1-100`, `VISION.md:53-69`
**Evidence**: ClawHub distributes plugins and skills. CLI: search/install/update. SHA-256 integrity verification. "New skills should be published to ClawHub first, not added to core."
**Relevance**: Skills as independently distributable units — distributable as Maven/Gradle artifacts or zip archives.

### Finding 11: Memory as Exclusive Plugin Slot

**Source**: `src/plugins/memory-state.ts:1-327`, `src/plugins/slots.ts:1-163`
**Evidence**: Memory is an exclusive plugin slot (only one active). `MemoryPluginCapability` has 4 concerns: promptBuilder, flushPlanResolver, runtime, publicArtifacts. "Corpus supplements" can be registered by multiple plugins to augment search.
**Relevance**: Kukuvaia's `kukuvaia-memory` module could use exclusive slot pattern for swappable memory implementations while keeping interface stable.

### Finding 12: Automation Hooks -- Event-Driven Lifecycle

**Source**: `docs/automation/hooks.md:1-303`
**Evidence**: 12 event types (command, session, agent, gateway, message lifecycle). Hooks are directories with `HOOK.md` + `handler.ts`. Plugin SDK exposes 28 additional hooks.
**Relevance**: Maps to Spring AI Advisor chain + Spring `ApplicationEvent` system. `@EventListener` methods with custom event types.

### Finding 13: Channel createChatChannelPlugin Builder

**Source**: `docs/plugins/sdk-channel-plugins.md:314-418`
**Evidence**: Builder function accepting declarative options for channel composition: base, security, pairing, threading, outbound.
**Relevance**: `ChannelDefinition.builder()` fluent API or Spring `@Configuration` class for declarative channel wiring.

### Finding 14: Skill Eligibility and Dependency Requirements

**Source**: `skills/github/SKILL.md:6-28`
**Evidence**: Skills declare requirements: `requires: { bins: ["gh"] }`, `requires: { anyBins: ["claude", "codex"] }`, install instructions. `openclaw skills check` verifies eligibility.
**Relevance**: Kukuvaia skills could declare requirements (MCP server, API keys, tools) in metadata for runtime eligibility checking.

## Patterns Identified

1. **Composition-of-Adapters**: ~25 optional typed adapter objects, not class hierarchies
2. **Core-Owns-Tool / Plugin-Owns-Dispatch**: Shared tool in core, channels own discovery and execution
3. **Registry-Based Discovery**: Channels through plugin registry, core extension-agnostic
4. **Binding-Based Routing**: Deterministic cascade from specific to general
5. **Session Key Grammar**: Channel-aware keys encoding context for isolation
6. **Capability Flags**: Static booleans declaring channel support
7. **DM Security Policy**: Per-channel allowlist/pairing/open policies
8. **Markdown-First Skills**: SKILL.md frontmatter, lazy-loaded on demand
9. **Multi-Source Discovery with Precedence**: bundled < plugin < managed < workspace
10. **Exclusive Plugin Slots**: One active at a time (memory)
11. **Corpus Supplements**: Multiple plugins augmenting search
12. **Event-Driven Hooks**: Lifecycle events with directory-based handler discovery
13. **Declarative Builder**: High-level builder composing low-level adapters
14. **Skill Requirements**: Declarative dependency requirements with eligibility checking

## Source Citations

| File | Notes |
|------|-------|
| `src/channels/plugins/types.plugin.ts` | Full ChannelPlugin type with all adapter surfaces |
| `src/channels/plugins/types.core.ts` | Core types: ChannelMeta, ChannelCapabilities |
| `src/channels/plugins/types.adapters.ts` | Adapter types: Setup, Config, Secrets |
| `src/channels/AGENTS.md` | Channel boundary rules |
| `src/channels/registry.ts` | Plugin-backed discovery |
| `src/routing/resolve-route.ts` | Agent routing cascade |
| `src/plugins/memory-state.ts` | Memory exclusive slot |
| `src/plugins/slots.ts` | Plugin slot system |
| `src/agents/skills/skill-contract.ts` | Skill type, XML formatting |
| `src/agents/skills/local-loader.ts` | Skill discovery and parsing |
| `src/agents/skills/workspace.ts` | Workspace skill resolution |
| `src/plugins/clawhub.ts` | ClawHub marketplace |
| `docs/plugins/sdk-channel-plugins.md` | Channel plugin building guide |
| `docs/plugins/architecture.md` | Plugin internals |
| `docs/channels/channel-routing.md` | Routing rules, session keys |
| `docs/channels/pairing.md` | DM pairing and allowlists |
| `docs/automation/hooks.md` | Hooks system |
| `skills/healthcheck/SKILL.md` | Example skill |
| `skills/github/SKILL.md` | Example with requirements |
| `VISION.md` | Skills on ClawHub, memory as exclusive slot |

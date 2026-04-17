# Findings: OpenClaw Plugin Architecture

## Summary

OpenClaw implements a mature, four-layer plugin system: manifest-driven discovery, enablement/validation, in-process runtime loading, and centralized registry consumption. The architecture enforces a strict separation between metadata (manifest) and behavior (runtime module), uses a typed capability model with 12+ registration methods on a central `OpenClawPluginApi` object, and supports exclusive slot selection, priority-ordered hooks, and lazy loading modes.

## Key Findings

### Finding 1: Manifest-First Architecture (Control Plane / Data Plane Split)

**Source**: `docs/plugins/architecture.md:148-152`, `docs/plugins/manifest.md:30-51`
**Evidence**: `openclaw.plugin.json` is read BEFORE any plugin code executes. Provides plugin identity, config validation (JSON Schema), auth metadata, capability ownership snapshots, channel config, UI hints, model matching. Enables config validation, UI generation, plugin listing, diagnostics WITHOUT executing untrusted code.
**Relevance**: Maps to `META-INF/plugin-manifest.json` or YAML descriptor validated at startup before Spring beans are created.

### Finding 2: Typed Capability Registration Model

**Source**: `docs/plugins/sdk-overview.md:298-340`, `docs/plugins/architecture.md:26-87`
**Evidence**: 12+ capability methods on `OpenClawPluginApi`: registerProvider, registerChannel, registerSpeechProvider, registerRealtimeTranscriptionProvider, registerRealtimeVoiceProvider, registerMediaUnderstandingProvider, registerImageGenerationProvider, registerVideoGenerationProvider, registerWebFetchProvider, registerWebSearchProvider, registerTool, registerCommand, registerHook, registerHttpRoute, registerService, registerContextEngine, registerMemoryCapability. Plugins classified into shapes: plain-capability, hybrid-capability, hook-only, non-capability.
**Relevance**: Maps to Spring `@Component`/`@Service` with marker interfaces. Define explicit capability interfaces that plugins implement.

### Finding 3: Plugin Lifecycle Pipeline (8-Step Load Sequence)

**Source**: `docs/plugins/architecture.md:492-507`
**Evidence**: 8 steps: Discover → Read manifests → Safety gates → Normalize config → Decide enablement → Load modules → Call register(api) → Expose registry. Safety gates before runtime execution. Discovery works from manifest metadata alone.
**Relevance**: Spring Boot auto-config is simpler. Multi-stage with explicit safety gates valuable for `.kukuvaia/` user-contributed plugins.

### Finding 4: Exclusive Slot System

**Source**: `src/plugins/slots.ts:1-162`
**Evidence**: Memory and context-engine are exclusive slots -- only one plugin active at a time. `applyExclusiveSlotSelection` auto-disables competing plugins.
**Relevance**: Directly applicable to kukuvaia's memory system. Implementable as `@ConditionalOnMissingBean` + slot config or custom `@ExclusiveSlot` annotation.

### Finding 5: Plugin Hook System (Priority-Ordered Event Pipeline)

**Source**: `src/plugins/hooks.ts:1-220`
**Evidence**: 30+ hook types (before_agent_start, before_tool_call, after_tool_call, before_model_resolve, message_received, etc.). Priority-ordered, terminal semantics (`block: true` stops chain), configurable fail-open/fail-closed per hook.
**Relevance**: Maps to Spring AI Advisor chain but with more granularity. Key additions: terminal stop semantics, fail-open/closed per hook, 30+ specific points.

### Finding 6: SDK Boundary Enforcement (Import Restrictions)

**Source**: `src/plugin-sdk/AGENTS.md:1-80`, `extensions/AGENTS.md:1-63`
**Evidence**: Extensions MUST import from `openclaw/plugin-sdk/*` only. MUST NOT import core `src/**` or another extension's internals. Narrow, purpose-built subpaths preferred.
**Relevance**: Maps to Java Module System (JPMS) or Gradle module boundaries. `kukuvaia-plugin-api` module that plugins depend on, no access to `kukuvaia-core` internals.

### Finding 7: Multi-Mode Registration (Lazy Loading)

**Source**: `docs/plugins/sdk-entrypoints.md:149-191`
**Evidence**: `registrationMode`: `"full"` (normal), `"setup-only"` (disabled channel), `"setup-runtime"` (setup flow), `"cli-metadata"` (help). Mode-gated registration reduces startup cost.
**Relevance**: Maps to Spring profiles or `@ConditionalOn*`. Plugins with metadata-only, setup, and full activation levels.

### Finding 8: Capability Ownership Model

**Source**: `docs/plugins/architecture.md:223-300`
**Evidence**: Plugin = ownership boundary. Capability = core contract. Company plugin owns ALL surfaces. Channels consume core capabilities, not vendor code. Adding new domain: define capability → expose API → wire channels → let vendors register.
**Relevance**: Maps to Spring's interface-based design. Define capability interfaces; plugins implement them.

### Finding 9: Contract Testing and Enforcement

**Source**: `docs/plugins/architecture.md:389-437`, `src/plugins/contracts/registry.ts`
**Evidence**: Runtime validation during loading (duplicates, malformed). Test-time contract snapshots (`PluginRegistrationContractEntry`) tracking per-plugin capability registrations.
**Relevance**: Integration tests verifying bean registration matches plugin declarations.

### Finding 10: Provider Runtime Hook Chain (44 Hook Points)

**Source**: `docs/plugins/architecture.md:609-719`
**Evidence**: 44 documented hook points in defined order: config → normalization → auth resolution → model resolution → runtime → operational. Fallthrough behavior across providers.
**Relevance**: Subset maps to Spring AI Advisor with provider-specific customization. Fallthrough = `@Order` + chain of responsibility.

### Finding 11: Entry Point Helper Hierarchy

**Source**: `src/plugin-sdk/plugin-entry.ts`, `src/plugin-sdk/core.ts`, `src/plugin-sdk/provider-entry.ts`
**Evidence**: Three levels: `definePluginEntry` (generic) → `defineChannelPluginEntry` (channel wiring) → `defineSingleProviderPluginEntry` (declarative provider setup).
**Relevance**: Maps to abstract base classes or `@KukuvaiaProvider` annotations.

### Finding 12: In-Process Trust Model with Allowlisting

**Source**: `docs/plugins/architecture.md:442-472`
**Evidence**: Native plugins run in-process. `plugins.allow`/`plugins.deny` for allowlist/denylist. Safety gates check paths before loading. Workspace plugins shadow bundled ones.
**Relevance**: Java/Spring has same in-process model. Allowlist pattern via `kukuvaia.plugins.allow`/`deny` config property.

## Patterns Summary

| # | Pattern | Spring/Java Equivalent |
|---|---------|----------------------|
| 1 | Manifest-first validation | `META-INF/plugin-manifest.json` + custom loader |
| 2 | Typed capability model | Marker interfaces + `@Component` |
| 3 | Multi-stage load pipeline | Auto-config + custom guards |
| 4 | Exclusive slot selection | `@ConditionalOnMissingBean` + slot config |
| 5 | Priority-ordered hooks | Advisor chain + `@Order` |
| 6 | SDK boundary enforcement | JPMS / Gradle module boundaries |
| 7 | Multi-mode registration | Spring profiles + `@ConditionalOn*` |
| 8 | Capability ownership | Interface-based design |
| 9 | Contract testing | Integration tests + bean verification |
| 10 | Provider hook chain | Advisor chain with provider hooks |
| 11 | Entry point helpers | Abstract base classes, annotations |
| 12 | In-process trust + allowlist | Classpath scanning + allowlist config |

## Source Citations

| File | Notes |
|------|-------|
| `src/plugin-sdk/AGENTS.md` | SDK boundary rules |
| `src/plugins/AGENTS.md` | Plugin subsystem boundary |
| `extensions/AGENTS.md` | Extension rules |
| `docs/plugins/architecture.md` | Deep architecture: capability model, ownership, contracts, hooks, trust |
| `docs/plugins/manifest.md` | Full manifest spec |
| `docs/plugins/sdk-overview.md` | SDK reference: 200+ subpaths, registration API |
| `docs/plugins/sdk-entrypoints.md` | Entry points, 4 registration modes |
| `docs/plugins/building-plugins.md` | Quick-start guide |
| `src/plugin-sdk/plugin-entry.ts` | definePluginEntry implementation |
| `src/plugin-sdk/core.ts` | defineChannelPluginEntry |
| `src/plugin-sdk/provider-entry.ts` | defineSingleProviderPluginEntry |
| `src/plugin-sdk/channel-contract.ts` | Channel contract type re-exports |
| `src/plugins/contracts/registry.ts` | Contract registry |
| `src/plugins/slots.ts` | Exclusive slot system |
| `src/plugins/hooks.ts` | Hook runner |
| `extensions/anthropic/openclaw.plugin.json` | Anthropic manifest example |
| `extensions/openai/openclaw.plugin.json` | OpenAI manifest example |
| `extensions/discord/openclaw.plugin.json` | Discord manifest example |

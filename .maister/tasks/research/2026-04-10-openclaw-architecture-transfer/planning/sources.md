# Research Sources

## OpenClaw Codebase Sources

### Root Documentation
- `/home/bartek/Projects/openclaw/AGENTS.md` -- Main architecture guide (42KB, symlinked as CLAUDE.md). Contains project structure, architecture boundaries, plugin/channel/provider/gateway boundaries, docs linking, coding style, build/test gates, prompt cache stability, multi-agent safety rules, commit/PR guidelines
- `/home/bartek/Projects/openclaw/VISION.md` -- Project vision, priorities, plugin/memory/skills/MCP strategy
- `/home/bartek/Projects/openclaw/SECURITY.md` -- Security policy, operator trust model, reporting requirements, common false-positive patterns
- `/home/bartek/Projects/openclaw/CONTRIBUTING.md` -- Contribution guidelines
- `/home/bartek/Projects/openclaw/docs.acp.md` -- ACP bridge documentation, compatibility matrix, session mapping

### Boundary Guides (AGENTS.md)
- `/home/bartek/Projects/openclaw/src/plugin-sdk/AGENTS.md` -- Plugin SDK boundary rules, source of truth, verification, expanding the boundary
- `/home/bartek/Projects/openclaw/src/channels/AGENTS.md` -- Channel boundary rules, public contracts, hot import path rules
- `/home/bartek/Projects/openclaw/src/plugins/AGENTS.md` -- Plugins boundary (discovery, manifest validation, loader, registry, contract enforcement)
- `/home/bartek/Projects/openclaw/extensions/AGENTS.md` -- Extension boundary rules

### Plugin System (`src/plugins/`)
- `src/plugins/types.ts` (104KB) -- Core plugin type definitions (comprehensive)
- `src/plugins/manifest.ts` -- Manifest validation and parsing
- `src/plugins/loader.ts` (74KB) -- Plugin loader pipeline
- `src/plugins/registry.ts` (47KB) -- Plugin registry assembly
- `src/plugins/discovery.ts` (28KB) -- Plugin discovery
- `src/plugins/hooks.ts` (36KB) -- Plugin hook system (before-agent-reply, before-tool-call, etc.)
- `src/plugins/clawhub.ts` (29KB) -- ClawHub marketplace integration
- `src/plugins/marketplace.ts` (34KB) -- Marketplace operations
- `src/plugins/commands.ts` -- Plugin CLI commands
- `src/plugins/config-state.ts` -- Plugin config state management
- `src/plugins/config-contracts.ts` -- Config contract enforcement
- `src/plugins/conversation-binding.ts` (31KB) -- Plugin conversation binding
- `src/plugins/slots.ts` -- Plugin slot system
- `src/plugins/memory-state.ts` -- Memory plugin slot management
- `src/plugins/doctor-contract-registry.ts` -- Doctor contracts for plugins
- `src/plugins/install.ts` (32KB) -- Plugin installation
- `src/plugins/uninstall.ts` -- Plugin uninstallation
- `src/plugins/update.ts` -- Plugin updates
- `src/plugins/status.ts` -- Plugin status reporting
- `src/plugins/contracts/` -- Plugin contract definitions

### Plugin SDK (`src/plugin-sdk/`)
- `src/plugin-sdk/plugin-entry.ts` -- definePluginEntry (main plugin entry point definition)
- `src/plugin-sdk/core.ts` -- Core SDK contract (defineChannelPluginEntry, etc.)
- `src/plugin-sdk/provider-entry.ts` -- Provider plugin entry point
- `src/plugin-sdk/channel-contract.ts` -- Channel plugin contract
- `src/plugin-sdk/allow-from.ts` -- Allowlist implementation
- `src/plugin-sdk/approval-*.ts` -- Approval system files (approvers, auth-helpers, client-helpers, delivery-helpers, handler, native-helpers, renderers)
- `src/plugin-sdk/acp-runtime.ts` -- ACP runtime SDK
- `src/plugin-sdk/agent-runtime.ts` -- Agent runtime SDK
- `src/plugin-sdk/api-baseline.ts` -- API baseline definitions
- `src/plugin-sdk/account-core.ts` -- Account core abstractions
- `src/plugin-sdk/allowlist-config-edit.ts` -- Allowlist config editing

### Channels (`src/channels/`)
- `src/channels/plugins/types.plugin.ts` -- Channel plugin types
- `src/channels/plugins/types.core.ts` -- Core channel types
- `src/channels/plugins/types.adapters.ts` -- Channel adapter types
- `src/channels/registry.ts` -- Channel registry
- `src/channels/session.ts` -- Channel session management
- `src/channels/allow-from.ts` -- Channel allowlist matching
- `src/channels/allowlist-match.ts` -- Allowlist match logic
- `src/channels/channel-config.ts` -- Channel configuration
- `src/channels/mention-gating.ts` -- Mention gating
- `src/channels/model-overrides.ts` -- Per-channel model overrides
- `src/channels/command-gating.ts` -- Command gating per channel
- `src/channels/typing.ts` -- Typing indicators
- `src/channels/status-reactions.ts` -- Status reactions
- `src/channels/targets.ts` -- Channel targets
- `src/channels/transport/` -- Transport layer

### ACP Bridge (`src/acp/`)
- `src/acp/client.ts` (14KB) -- ACP client implementation
- `src/acp/server.ts` (8KB) -- ACP server
- `src/acp/translator.ts` (44KB) -- ACP-Gateway event translator
- `src/acp/session.ts` (6KB) -- ACP session management
- `src/acp/event-mapper.ts` -- Event mapping
- `src/acp/commands.ts` -- ACP commands
- `src/acp/policy.ts` -- ACP policy
- `src/acp/approval-classifier.ts` -- Approval classification for ACP

### Commands (`src/commands/`)
- `src/commands/agent/` -- Agent command subdirectory
- `src/commands/doctor*.ts` -- Doctor/diagnostic command files
- `src/commands/onboard*.ts` -- Onboarding commands

### Skills Directory (`skills/`)
53 skills total, including:
- `skills/healthcheck/` -- Health check skill
- `skills/github/` -- GitHub integration skill
- `skills/coding-agent/` -- Coding agent skill
- `skills/canvas/` -- Canvas skill
- `skills/clawhub/` -- ClawHub skill
- `skills/discord/` -- Discord skill
- `skills/gemini/` -- Gemini skill

### Extensions Directory (`extensions/`)
109 extensions total, including providers, channels, and tool plugins:
- Provider extensions: `extensions/anthropic/`, `extensions/openai/`, `extensions/google/`, `extensions/groq/`, `extensions/deepseek/`, etc.
- Channel extensions: `extensions/discord/`, `extensions/matrix/`, `extensions/feishu/`, `extensions/googlechat/`, etc.
- Tool extensions: `extensions/browser/`, `extensions/brave/`, `extensions/firecrawl/`, `extensions/duckduckgo/`, etc.
- Special extensions: `extensions/active-memory/`, `extensions/diffs/`, `extensions/diagnostics-otel/`, etc.

---

## OpenClaw Documentation Sources

### Plugin Documentation (`docs/plugins/`)
- `docs/plugins/architecture.md` -- Plugin internals: capability model, ownership, contracts, load pipeline
- `docs/plugins/manifest.md` -- Plugin manifest spec (openclaw.plugin.json)
- `docs/plugins/sdk-overview.md` -- SDK import map and registration API
- `docs/plugins/sdk-entrypoints.md` -- Entry point reference (definePluginEntry, defineChannelPluginEntry)
- `docs/plugins/sdk-runtime.md` -- SDK runtime helpers
- `docs/plugins/sdk-channel-plugins.md` -- Channel plugin building guide
- `docs/plugins/sdk-provider-plugins.md` -- Provider plugin building guide
- `docs/plugins/building-plugins.md` -- Getting started with plugins
- `docs/plugins/building-extensions.md` -- Building extensions
- `docs/plugins/sdk-setup.md` -- SDK setup
- `docs/plugins/sdk-testing.md` -- SDK testing
- `docs/plugins/sdk-migration.md` -- SDK migration guide
- `docs/plugins/community.md` -- Community plugins
- `docs/plugins/bundles.md` -- Plugin bundles
- `docs/plugins/agent-tools.md` -- Agent tools
- `docs/plugins/webhooks.md` -- Plugin webhooks
- `docs/plugins/memory-wiki.md` -- Memory/wiki plugins

### Gateway Documentation (`docs/gateway/`)
- `docs/gateway/doctor.md` -- Doctor command docs
- `docs/gateway/sandboxing.md` -- Sandbox architecture (modes, scopes, backends)
- `docs/gateway/sandbox-vs-tool-policy-vs-elevated.md` -- Security policy comparison
- `docs/gateway/security/index.md` -- Security guidance and trust model
- `docs/gateway/protocol.md` -- Gateway wire protocol
- `docs/gateway/bridge-protocol.md` -- Bridge protocol
- `docs/gateway/pairing.md` -- Device pairing
- `docs/gateway/secrets.md` -- Secrets management
- `docs/gateway/secrets-plan-contract.md` -- Secrets plan contract
- `docs/gateway/configuration.md` -- Gateway configuration
- `docs/gateway/configuration-reference.md` -- Config reference
- `docs/gateway/trusted-proxy-auth.md` -- Trusted proxy auth

### Concept Documentation (`docs/concepts/`)
- `docs/concepts/architecture.md` -- Gateway architecture, components, flows
- `docs/concepts/agent.md` -- Agent concepts
- `docs/concepts/session.md` -- Session concepts
- `docs/concepts/context.md` -- Context concepts
- `docs/concepts/context-engine.md` -- Context engine
- `docs/concepts/memory-honcho.md` -- Memory (Honcho)
- `docs/concepts/model-providers.md` -- Model provider concepts (referenced in AGENTS.md)
- `docs/concepts/queue.md` -- Queue concepts
- `docs/concepts/oauth.md` -- OAuth concepts

### Channel Documentation (`docs/channels/`)
- `docs/channels/index.md` -- Channels overview
- `docs/channels/channel-routing.md` -- Channel routing
- `docs/channels/pairing.md` -- Channel pairing
- `docs/channels/group-messages.md` -- Group message handling
- `docs/channels/broadcast-groups.md` -- Broadcast groups
- Plus individual channel docs (telegram, discord, slack, signal, imessage, whatsapp, matrix, etc.)

### CLI Documentation (`docs/cli/`)
- `docs/cli/doctor.md` -- Doctor CLI
- `docs/cli/security.md` -- Security CLI
- `docs/cli/sandbox.md` -- Sandbox CLI
- `docs/cli/approvals.md` -- Approvals CLI
- `docs/cli/acp.md` -- ACP CLI
- `docs/cli/skills.md` -- Skills CLI
- `docs/cli/plugins.md` -- Plugins CLI
- `docs/cli/agents.md` -- Agents CLI
- `docs/cli/sessions.md` -- Sessions CLI
- `docs/cli/memory.md` -- Memory CLI
- `docs/cli/hooks.md` -- Hooks CLI
- `docs/cli/config.md` -- Config CLI

### Automation Documentation (`docs/automation/`)
- `docs/automation/hooks.md` -- Automation hooks
- `docs/automation/cron-jobs.md` -- Cron jobs
- `docs/automation/taskflow.md` -- Taskflow
- `docs/automation/standing-orders.md` -- Standing orders
- `docs/automation/webhook.md` -- Webhooks
- `docs/automation/poll.md` -- Polling

### Diagnostics Documentation (`docs/diagnostics/`)
- `docs/diagnostics/` -- Diagnostic documentation files

---

## Kukuvaia Sources

### Project Documentation (`.maister/docs/project/`)
- `.maister/docs/project/architecture.md` -- System architecture (modules, data flow, integrations)
- `.maister/docs/project/implementation-plan.md` -- Three-phase implementation plan (Foundation, Document Agent, Intelligence Layer)
- `.maister/docs/project/roadmap.md` -- Development priorities (high/medium/future)
- `.maister/docs/project/tech-stack.md` -- Technology choices and rationale
- `.maister/docs/project/vision.md` -- Project purpose and goals
- `.maister/docs/project/work-log.md` -- Implementation work log
- `.maister/docs/project/cli-implementation-plan.md` -- CLI implementation plan

### Architecture Documentation (`docs/architecture/`)
- `docs/architecture/auth-and-providers.md` -- Authentication and provider routing
- `docs/architecture/memory-architecture.md` -- Memory system (3 types, pgvector, JSONB)
- `docs/architecture/embabel-integration.md` -- Embabel/GOAP agent integration
- `docs/architecture/security-review.md` -- Security review
- `docs/architecture/tool-filtering.md` -- Tool filtering per persona
- `docs/architecture/health-resilience.md` -- Health and resilience patterns
- `docs/architecture/design-system.md` -- Design system (kukuvaia-theme.yaml)

### Analysis Documentation (`docs/analyzes/`)
- `docs/analyzes/agent-capabilities-gap-analysis.md` -- Gap analysis of agent capabilities

### Standards Documentation (`.maister/docs/standards/`)
- `.maister/docs/standards/security/credentials.md` -- Credentials management
- `.maister/docs/standards/security/authentication.md` -- API authentication
- `.maister/docs/standards/security/injection-prevention.md` -- Injection prevention
- `.maister/docs/standards/security/runtime.md` -- Runtime security
- `.maister/docs/standards/security/agent-isolation.md` -- Agent isolation
- `.maister/docs/standards/backend/architecture.md` -- Backend architecture standards
- `.maister/docs/standards/backend/api.md` -- API design standards

### Root Documentation
- `CLAUDE.md` -- Project overview, architecture, key decisions, design system, database, API contract

---

## Configuration Sources

### OpenClaw Configuration
- `/home/bartek/Projects/openclaw/.env.example` -- Environment variables
- `/home/bartek/Projects/openclaw/docker-compose.yml` -- Docker services
- `/home/bartek/Projects/openclaw/Dockerfile` -- Main Dockerfile
- `/home/bartek/Projects/openclaw/Dockerfile.sandbox` -- Sandbox Dockerfile
- `/home/bartek/Projects/openclaw/Dockerfile.sandbox-browser` -- Browser sandbox Dockerfile
- `/home/bartek/Projects/openclaw/Dockerfile.sandbox-common` -- Common sandbox base
- `/home/bartek/Projects/openclaw/package.json` -- Dependencies, scripts, plugin SDK exports
- `/home/bartek/Projects/openclaw/knip.config.ts` -- Dead code detection config

### Kukuvaia Configuration
- `/home/bartek/Projects/kukuvaia/kukuvaia-theme.yaml` -- Design system theme (if exists)
- `/home/bartek/Projects/kukuvaia/.maister/docs/INDEX.md` -- Documentation index

---

## Source Priority

### Critical (must read for research to succeed)
1. OpenClaw AGENTS.md -- architecture boundaries, multi-agent safety, gates
2. OpenClaw src/plugins/types.ts -- plugin type system
3. OpenClaw docs/plugins/architecture.md -- plugin internals
4. OpenClaw docs/gateway/security/index.md -- security model
5. OpenClaw docs/gateway/sandboxing.md -- sandbox architecture
6. OpenClaw src/plugin-sdk/AGENTS.md -- SDK boundary
7. Kukuvaia .maister/docs/project/architecture.md -- current architecture
8. Kukuvaia .maister/docs/project/implementation-plan.md -- planned features

### Important (significantly enriches analysis)
9. OpenClaw docs/plugins/manifest.md -- manifest spec
10. OpenClaw docs/plugins/sdk-entrypoints.md -- entry points
11. OpenClaw src/channels/AGENTS.md -- channel boundary
12. OpenClaw docs.acp.md -- ACP bridge
13. OpenClaw docs/gateway/doctor.md -- doctor command
14. OpenClaw VISION.md -- project direction
15. Kukuvaia docs/architecture/*.md -- all architecture docs
16. Kukuvaia .maister/docs/standards/security/*.md -- security standards

### Supplementary (adds depth and detail)
17. OpenClaw src/plugins/hooks.ts -- hook implementation
18. OpenClaw src/acp/translator.ts -- ACP translator
19. OpenClaw src/channels/plugins/types.*.ts -- channel types
20. OpenClaw docs/automation/hooks.md -- automation hooks
21. OpenClaw skills/ directory structure -- skill patterns
22. Kukuvaia docs/analyzes/agent-capabilities-gap-analysis.md -- gap analysis
23. Kukuvaia .maister/docs/project/roadmap.md -- priorities

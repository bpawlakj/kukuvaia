# System Architecture

## Overview
Kukuvaia follows a distributed client-server architecture where multiple thin clients (CLI, future web) connect to a single intelligent engine via HTTP/SSE. The engine is a multi-module Gradle project: `kukuvaia-core` (Java) handles domain logic, `kukuvaia-agents` (Kotlin/Embabel) provides GOAP agent orchestration, `kukuvaia-memory` (Java) manages persistent knowledge, and `kukuvaia-app` is the Spring Boot entry point. Clients are pure presentation layers.

## Architecture Pattern
**Pattern**: Multi-module engine with shared API contract

```
┌─────────────┐                    ┌──────────────────────────────────────┐
│ kukuvaia-cli │  HTTP/SSE          │  kukuvaia-engine (multi-module)      │
│ (Go/Charm)  │◄──────────────────►│                                      │
└─────────────┘                    │  kukuvaia-core (Java/Spring AI)      │
                                   │    ChatClient + Advisors + @McpTool  │
┌─────────────┐  HTTP/SSE          │                                      │
│ kukuvaia-web │◄──────────────────►│  kukuvaia-agents (Kotlin/Embabel)   │
│ (future)    │                    │    GOAP planning + multi-model LLM   │
└─────────────┘                    │                                      │
                                   │  kukuvaia-memory (Java)              │
                                   │    Semantic search + JSONB sessions  │
                                   │                                      │
                                   │  kukuvaia-app (Boot entry point)     │
                                   └──────────┬───────────────────────────┘
                                              │
                                   ┌──────────┴──────────┐
                                   │                     │
                           ┌───────▼──────┐      ┌──────▼───────┐
                           │ PostgreSQL   │      │ MongoDB      │
                           │ + pgvector   │      │ (ETSL, R/O)  │
                           │ kukuvaia_agent│      └──────────────┘
                           │ kukuvaia_data │
                           └──────────────┘
```

## Module Dependencies
```
kukuvaia-memory              ← standalone (Spring JDBC, pgvector, Spring AI memory API)
kukuvaia-core                ← depends on :kukuvaia-memory
kukuvaia-agents              ← depends on :kukuvaia-core + :kukuvaia-memory + Embabel 0.3.4
kukuvaia-app                 ← depends on all (Boot entry + migrations)
```

## System Structure

### Agent Core (`ai.kukuvaia.agent`) — kukuvaia-core
- **Purpose**: LLM conversation orchestration with tool calling
- **Key Classes**: AgentService (interactive chat), DaemonAgentService (autonomous tasks)
- **Pattern**: Spring AI ChatClient with composable Advisor chain

### Embabel Agent Orchestration (`ai.kukuvaia.agents`) — kukuvaia-agents
- **Purpose**: GOAP-based autonomous agent planning and multi-agent orchestration
- **Key Classes**: PingAgent (framework test), ResearchAgent (planned), ValidationAgent (planned)
- **Pattern**: Embabel @Agent/@Action/@AchievesGoal with Blackboard state, deterministic GOAP planning
- **See**: [Embabel Integration](../../docs/architecture/embabel-integration.md)

### Memory System (`ai.kukuvaia.memory`) — kukuvaia-memory
- **Purpose**: Persistent knowledge management with semantic search
- **Key Classes**: SmartMemoryRepository (pgvector + FTS), JsonChatMemoryRepository (JSONB conversations), SmartMemoryAdvisor (top-K retrieval), SessionRepository (user-session linkage)
- **Memory Types**: episodic (what happened), semantic (what we know), procedural (how we do things)
- **See**: [Memory Architecture](../../docs/architecture/memory-architecture.md)

### Sub-Agent System (`ai.kukuvaia.agent.subagent`)
- **Purpose**: Isolated specialist delegation with privilege controls
- **Key Classes**: SubAgentFactory (spawns isolated ChatClient), SubAgentGuard (tool filtering, depth limits, prompt hardening), SubAgentSpec (YAML persona loader)
- **Constraints**: Max depth=1 (no recursive spawning), tools intersected with persona scope

### Daemon System (`ai.kukuvaia.agent.daemon`)
- **Purpose**: Autonomous background task execution
- **Key Classes**: DaemonAgentService, DaemonBudgetGuard (daily token limit), DaemonScheduleGuard (skip-if-running), PgNotifyDebouncer (batch events)
- **Triggers**: @Scheduled cron, webhook POST, PG NOTIFY

### Security Layer (`ai.kukuvaia.security`)
- **Purpose**: Defense-in-depth across all entry points
- **Key Classes**: ApiAuthFilter (Bearer/JWT), WebhookAuthFilter (HMAC-SHA256), WebhookRateLimiter, ErrorSanitizer, PayloadSanitizer, ToolResultSanitizingAdvisor

### Provider Routing (`ai.kukuvaia.provider`)
- **Purpose**: Multi-provider LLM access with runtime switching
- **Key Classes**: LlmProviderService (Copilot/SmartGate routing), ProviderAuditLog (Spring AI advisor for all LLM calls)

### Web API (`ai.kukuvaia.api`)
- **Purpose**: HTTP/SSE endpoints for client communication
- **Endpoints**: POST /api/chat (SSE stream), GET /api/sessions, POST /api/commands/{cmd}
- **Output**: Structured blocks (TextBlock, TableBlock, CodeBlock, ProgressBlock) serialized as JSON

## Data Flow

### Interactive Chat
1. User input → CLI → `POST /api/chat` → ChatController
2. Advisor chain: ProviderAuditLog → ToolResultSanitizingAdvisor → SmartMemoryAdvisor → MessageChatMemoryAdvisor → ToolCallAdvisor
3. ChatClient routes to LLM provider (Copilot/SmartGate)
4. LLM may invoke @McpTool tools → parameterized queries → results back to LLM
5. Final response → structured OutputBlocks → SSE stream → CLI renders with Charm

### Daemon Task
1. Trigger: cron / webhook / PG NOTIFY
2. WebhookAuthFilter → PayloadSanitizer → DaemonAgentService
3. Budget check → Schedule check → SubAgent execution
4. Result → daemon_tasks audit trail → notification to external sink (sanitized)

## External Integrations
| Integration | Protocol | Purpose |
|-------------|----------|---------|
| PostgreSQL + pgvector | JDBC | Sessions, memory (semantic search), conversations (JSONB), pipeline data |
| MongoDB | Driver | Read-only outline/content access |
| GitHub Copilot | OpenAI-compatible HTTP | LLM provider (OAuth device flow) |
| SmartGate | OpenAI-compatible HTTP | LLM provider (JWT) |
| Authorsuite | HTTP REST | Validation API via @McpTool |

## Configuration
- **Secrets**: Environment variables (never hardcoded) — local dev via `kukuvaia-engine/.env` (auto-loaded by `bootRun`), production via secret manager. OAuth credential storage will be reintroduced when `/login github` is implemented.
- **Personas**: YAML files in `.kukuvaia/` directory with tool_filter, system prompt, provider restrictions
- **Design tokens**: `kukuvaia-theme.yaml` (shared between CLI and web)

## Deployment Architecture
- **Current**: Single server instance, single PG instance (two schemas)
- **Scaling path**: Extract tools to separate MCP server — Spring AI MCP Client handles transparently (connection string change, zero code change)
- **Database split**: Two PG instances possible — connection string change only

---
*Updated 2026-04-10: Multi-module structure, Embabel integration, memory architecture*

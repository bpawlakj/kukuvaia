# CLAUDE.md — kukuvaia-engine

## What This Is

Java/Spring Boot + Spring AI agent engine. Multi-module Gradle project handling LLM conversation, tool execution, sessions, memory, personas, agent orchestration, and Web API (SSE).

## Tech Stack

- Java 21+ (core, app) / Kotlin 2.x (agents)
- Spring Boot 3.x
- Spring AI 1.x — ChatClient, ToolCallAdvisor, MCP, JdbcChatMemory
- Embabel Agent Framework (agents module — Kotlin, GOAP planning)
- PostgreSQL — sessions (JdbcChatMemoryRepository) + pipeline data (JDBC)
- HTTP client — Authorsuite validation API

## Multi-Module Structure

```
kukuvaia-engine/
├── kukuvaia-core/           # Java — all domain logic, tools, API, security
│   └── src/main/java/ai/kukuvaia/
│       ├── advisors/        # Spring AI advisors (intent, budget, hooks)
│       ├── agent/           # AgentService, PersonaService, CommandRouter
│       │   ├── daemon/      # Background task execution, budget guards
│       │   ├── memory/      # Persistent memory advisor + repository
│       │   └── subagent/    # Sub-agent factory, guard, specs
│       ├── api/             # REST controllers (chat, sessions, commands, webhooks)
│       ├── commands/        # Slash command implementations
│       ├── config/          # Spring configuration (ChatClient, Memory, ToolRegistry)
│       ├── extensions/      # .kukuvaia/ directory loader (rules, skills, commands)
│       ├── output/          # OutputBlock sealed hierarchy
│       ├── provider/        # LLM provider management + audit
│       ├── scripting/       # Lua sandbox + pipeline engine
│       ├── security/        # Auth filters, sanitizers, rate limiting
│       ├── skills/          # Skill execution framework
│       ├── tools/           # @McpTool definitions (memory, planning)
│       └── workflows/       # Workflow registry
│
├── kukuvaia-agents/         # Kotlin — Embabel agent orchestration
│   └── src/main/kotlin/ai/kukuvaia/agents/
│       └── (agent definitions with GOAP planning)
│
└── kukuvaia-app/            # Spring Boot entry point
    └── src/main/java/ai/kukuvaia/
        └── KukuvaiaApplication.java
    └── src/main/resources/
        ├── application.yaml
        └── db/migration/    # Flyway SQL migrations
```

## Module Responsibilities

| Module | Language | Purpose |
|--------|----------|---------|
| `kukuvaia-core` | Java | Domain logic, tools, API, security, extensions |
| `kukuvaia-agents` | Kotlin | Embabel agent orchestration (GOAP planning) |
| `kukuvaia-app` | Java | Spring Boot entry point, resources, migrations |

## Spring AI Components Used

| Component | Purpose |
|-----------|---------|
| `ChatClient` | Fluent API for LLM conversation |
| `ToolCallAdvisor` | Automatic multi-round tool calling loop |
| `MessageWindowChatMemory` | Windowed message history (context compaction) |
| `JdbcChatMemoryRepository` | PostgreSQL-backed session persistence |
| `MessageChatMemoryAdvisor` | Injects conversation history into prompts |
| `@McpTool` / `@McpToolParam` | Tool registration with auto JSON Schema |
| `OpenAiApi.mutate().baseUrl()` | SmartGate integration (OpenAI-compatible) |
| `Flux<ChatResponse>` | SSE streaming for Web API |

## Key Patterns

### Multi-Provider LLM (GitHub Copilot + SmartGate)

Both providers are OpenAI-compatible — same Spring AI `OpenAiApi`, different `baseUrl`.

### ChatClient with Memory + Tools

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
    return builder
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(chatMemory).build(),
            ToolCallAdvisor.builder().build()
        )
        .build();
}
```

### Typed Tools (@McpTool)

LLM never sees SQL. @McpTool builds parameterized queries internally. SQL injection eliminated by design.

### Command Dispatch

Slash commands are deterministic — no LLM parsing:
```
/validate all  → CommandRouter → ValidateCommand → @McpTool call → OutputBlocks
free text      → ChatClient + ToolCallAdvisor → automatic tool loop → OutputBlocks
```

### SSE Streaming

```java
@PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<OutputBlock> chat(@RequestBody ChatRequest request) {
    return agentService.streamChat(request.sessionId(), request.message());
}
```

## Security

Server does NOT expose raw SQL or MongoDB queries. Each backend registers typed tools with structured input schemas via `@McpTool`. Server builds parameterized queries internally.

## Database

Two PostgreSQL schemas:

| Schema | Tables | Purpose |
|--------|--------|---------|
| `kukuvaia_agent` | `SPRING_AI_CHAT_MEMORY`, `outline_memory` | Sessions, cross-session knowledge |
| `kukuvaia_data` | `outline_classifications`, `content_embeddings`, ... | Pipeline data (queried by @McpTool) |

## User Extensibility

Per-project `.kukuvaia/` directory:
- `rules/*.md` — always-on constraints → system prompt
- `skills/*/SKILL.md` — on-demand procedures → `/skill <name>`
- `commands/*.yaml` — user-defined slash commands
- `personas/*.yaml` — custom personas
- `settings.yaml` — config overrides

## Build & Run

```bash
cd kukuvaia-engine
./gradlew clean build         # build all modules
./gradlew :kukuvaia-app:bootRun  # start server
```

## Testing

```bash
./gradlew test                # run all tests
./gradlew :kukuvaia-core:test # run core tests only
```

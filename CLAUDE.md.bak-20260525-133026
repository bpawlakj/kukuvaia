# CLAUDE.md — Kukuvaia

## Coding Standards & Conventions

Read @.maister/docs/INDEX.md before starting any task. It indexes the project's coding standards and conventions:
- Coding standards organized by domain (global, security, backend, frontend, testing)
- Security standards: credentials, authentication, injection prevention, runtime, agent isolation
- Project vision, tech stack, and architecture decisions

Follow standards in `.maister/docs/standards/` when writing code — they represent team decisions. If standards conflict with the task, ask the user.

### Standards Evolution

When you notice recurring patterns, fixes, or conventions during implementation that aren't yet captured in standards — suggest adding them. Examples:
- A bug fix reveals a pattern that should be standardized (e.g., "always validate X before Y")
- PR review feedback identifies a convention the team wants enforced
- The same type of fix is needed across multiple files
- A new library/pattern is adopted that should be documented

When this happens, briefly suggest the standard to the user. If approved, invoke `/maister:standards-update` with the identified pattern.

## Maister Workflows

This project uses the maister plugin for structured development workflows. When any `/maister:*` command is invoked, execute it via the Skill tool immediately — do not skip workflows for "straightforward" tasks. The user chose the workflow intentionally; complexity assessment is the workflow's job.

## What This Is

Kukuvaia (κουκουβάγια — owl, symbol of wisdom) is an extensible conversational AI agent platform consisting of two independent components with separate repos and CI/CD pipelines:

1. **kukuvaia-engine** (Java/Spring Boot + Spring AI + Kotlin/Embabel) — Agent engine, LLM conversation, tools, sessions, memory, personas, agent orchestration, Web API (SSE)
2. **kukuvaia-cli** (Go, Charm stack) — Beautiful TUI client connecting to server Web API
3. **kukuvaia-web** (future) — Web frontend connecting to same server Web API

All clients share a unified design system (`kukuvaia-theme.yaml`) for visual consistency across terminal and browser.

This directory holds shared documentation, architecture decisions, and design docs. Each component is developed, versioned, and deployed independently.

## Architecture

```
User
  │
  ├── kukuvaia-cli (Go / Bubbletea + Lipgloss)
  │     TUI client — tables, progress, panels, markdown
  │     Styled via kukuvaia-theme.yaml → Lipgloss
  │     Connects to server via HTTP/SSE
  │
  ├── kukuvaia-web (future — React/Vue/Svelte)
  │     Web client — same visual language in browser
  │     Styled via kukuvaia-theme.yaml → CSS variables
  │     Connects to server via HTTP/SSE
  │
  └── Any HTTP/SSE client (custom integrations)
        │
        ▼
kukuvaia-engine (Java / Spring Boot + Spring AI + Kotlin / Embabel)
  │  kukuvaia-core     — domain logic, tools, API, security (Java)
  │  kukuvaia-agents   — agent orchestration, GOAP planning (Kotlin)
  │  kukuvaia-app      — Spring Boot entry point
  │  ChatClient + ToolCallAdvisor     — LLM agent loop
  │  MCP Server (@McpTool)            — exposes tools
  │  Personas + Commands + Extensions — custom behavior
  │
  ├── @McpTool → PostgreSQL (JDBC)
  │     get_classifications, get_groups, search_items, ...
  ├── @McpTool → MongoDB (driver)
  │     get_outline_raw, get_sections, get_content_items, ...
  ├── @McpTool → Authorsuite (HTTP)
  │     run_validation, get_validation_report, ...
  └── ... future: k8s, AWS, git, jira (@McpTool or MCP Client)
```

## Key Architecture Decisions

### Why single server (no separate gateway)

Spring AI provides both MCP Server (`@McpTool` annotations) and MCP Client (connects to external MCP servers) in the same application. Tools live in-process — zero HTTP overhead. If scaling is needed later, tools can be extracted to a separate MCP server without changing agent logic (Spring AI MCP Client handles this transparently).

### Why Go for CLI (not Java Spring Shell)

Spring Shell provides basic REPL with low-level TUI primitives. Go Charm stack (Bubbletea + Lipgloss + Bubbles) provides CSS-like styling, True Color, Elm architecture, and polished components used by Microsoft Azure, AWS, NVIDIA. CLI connects to server Web API — thin client, no agent logic.

### Why Spring AI

| What | Spring AI provides |
|------|-------------------|
| LLM agent loop | ChatClient + ToolCallAdvisor — automatic multi-round tool calling |
| Tool registration | @McpTool annotations — auto JSON Schema generation |
| Session persistence | JdbcChatMemoryRepository — PostgreSQL out-of-the-box |
| Context compaction | MessageWindowChatMemory — windowed message history |
| Multi-provider LLM | OpenAiApi — works with both GitHub Copilot and SmartGate (same interface) |
| SSE streaming | Flux<ChatResponse> — reactive streaming |
| Tool aggregation | MCP Client starter — connect to external MCP servers |

## Separation of Concerns

| Concern | kukuvaia-engine | kukuvaia-cli |
|---------|----------------|-------------|
| LLM conversation | Yes (Spring AI ChatClient) | No |
| Tool execution | Yes (@McpTool, in-process) | No |
| Session persistence | Yes (PG, JdbcChatMemoryRepository) | No |
| Personas / extensions | Yes | No |
| Rendering / TUI | No | Yes (Bubbletea + Lipgloss) |
| User input | Web API (SSE) | CLI REPL |

## Database

Single PostgreSQL instance, two schemas:

| Schema | Owner | Tables | Purpose |
|--------|-------|--------|---------|
| `kukuvaia_agent` | kukuvaia-engine | `SPRING_AI_CHAT_MEMORY`, `outline_memory` | Sessions, memory |
| `kukuvaia_data` | kukuvaia-engine | `outline_classifications`, `content_embeddings`, ... | Pipeline data (tools) |

**Now:** Two schemas in one PG instance.
**Later:** Split to two PG instances — change connection string, zero code change.

## Communication

CLI ↔ Server communicate via HTTP/SSE:

```
POST /api/chat          — send message, receive SSE stream
GET  /api/sessions      — list sessions
GET  /api/sessions/{id} — get session
POST /api/commands/{cmd} — execute slash command
```

Server returns structured output blocks (JSON) — CLI renders them with Charm components.

## Design System — Shared Theme

Single source of truth for visual identity across CLI (Lipgloss) and web (CSS). Defined in `kukuvaia-theme.yaml`, consumed by both clients.

### Theme File

```yaml
# kukuvaia-theme.yaml — Matrix/terminal aesthetic

colors:
  primary: "#00FF41"         # Phosphor green (Matrix)
  success: "#00FF41"
  error: "#FF4444"
  warning: "#FFB800"
  muted: "#008F11"           # Dark green
  text: "#00FF41"
  background: "#000000"     # Pure black
  surface: "#0A0A0A"
  border: "#008F11"

table:
  header_bg: "#008F11"
  header_fg: "#00FF41"
  row_alt_bg: "#0A0A0A"
  border_style: "rounded"

panel:
  border_style: "rounded"
  border_color: "#008F11"
  title_color: "#00FF41"
  padding: [0, 1]

code:
  background: "#0A0A0A"
  border_color: "#008F11"

progress:
  filled_color: "#00FF41"
  empty_color: "#008F11"

spacing:
  block_gap: 1
```

### How It Maps

| Token | Lipgloss (CLI) | CSS (Web) |
|-------|---------------|-----------|
| `colors.primary` | `Foreground(Color("#00FF41"))` | `color: var(--color-primary)` |
| `colors.background` | `Background(Color("#000000"))` | `background: var(--color-bg)` |
| `panel.border_style: rounded` | `Border(RoundedBorder())` | `border-radius: 8px` |
| `panel.padding: [0, 1]` | `Padding(0, 1)` | `padding: 0 0.5rem` |
| `table.header_bg` | `Background(Color("#008F11"))` | `background: var(--table-header-bg)` |

### Build Pipeline

```
kukuvaia-theme.yaml
       │
       ├── go generate → internal/tui/styles.go   (Lipgloss constants)
       └── build step  → static/theme.css          (CSS custom properties)
```

One file changed → both CLI and web update. Visual consistency guaranteed.

## Key Decisions

- **Multi-provider LLM** — GitHub Copilot (primary, OAuth device flow) + SmartGate (secondary, JWT). Both OpenAI-compatible → Spring AI `OpenAiApi` with different `baseUrl`
- **`/login` + `/model`** — authenticate with provider, select model at runtime
- **Spring AI ChatClient** — agent loop with automatic tool calling
- **@McpTool for typed tools** — no raw SQL, parameterized queries internally
- **Deterministic slash commands** — `/validate`, `/check` execute tools directly, no LLM
- **Free-form text** → ChatClient agent loop with tool_use
- **Structured output blocks** — TextBlock, TableBlock, CodeBlock, ProgressBlock — CLI renders with Charm
- **User extensibility** — `.kukuvaia/` directory with rules, skills, custom commands, custom personas

## Documentation

```
docs/
├── plan/          # Implementation plans, phase breakdowns
├── architecture/  # System design, data flow
├── analyzes/      # Research, evaluations, comparisons
├── tasks/         # Active work items, checklists
└── fix/           # Bug analysis, incident reports
```

## Repositories

| Component | Language | Stack |
|-----------|----------|-------|
| kukuvaia-engine | Java 21+ / Kotlin 2.x | Spring Boot 3.x + Spring AI 1.x + Embabel |
| kukuvaia-cli | Go 1.22+ | Bubbletea + Lipgloss + Bubbles |

API contract: HTTP/SSE (JSON). See `docs/architecture/`.

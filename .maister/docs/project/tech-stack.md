# Technology Stack

## Overview
This document describes the technology choices and rationale for Kukuvaia, a multi-component AI agent platform.

## Languages

### Java (21+)
- **Usage**: Primary language for kukuvaia-server (25+ source files, 1776 LOC)
- **Rationale**: Strong type safety, mature enterprise ecosystem, first-class Spring AI support
- **Key Features Used**: Text blocks, switch expressions, pattern matching (`instanceof`), records

### Kotlin (2.1.20)
- **Usage**: kukuvaia-agents module — Embabel agent orchestration (GOAP planning)
- **Rationale**: Embabel is Kotlin-first, data classes for typed blackboard state, coroutines for async actions, Spring plugin for DI compatibility
- **Key Features Used**: Data classes, extension functions, null safety, Spring annotations interop

### Go (1.22+)
- **Usage**: kukuvaia-cli — TUI client (single binary distribution)
- **Rationale**: Cross-platform compilation, excellent concurrency for SSE streaming, Charm stack ecosystem (used by Azure, AWS, NVIDIA)

## Frameworks

### Backend
| Framework | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 3.4.4 | Application container, DI, security filters, REST API |
| Spring AI | 1.1.0 | ChatClient, @McpTool annotations, MessageWindowChatMemory, composable Advisors, MCP Client/Server |
| Embabel Agent | 0.3.4 | GOAP planning, @Agent/@Action/@AchievesGoal, blackboard pattern, multi-model LLM |

### CLI (TUI)
| Library | Purpose |
|---------|---------|
| Bubbletea | Elm architecture — state management, event loop |
| Lipgloss | CSS-like terminal styling with True Color |
| Bubbles | Pre-built components (input, table, progress, spinner, viewport) |
| Glamour | Terminal markdown rendering |

### Testing
| Framework | Purpose |
|-----------|---------|
| JUnit 5 | Test runner, lifecycle management |
| AssertJ | Fluent assertion library |
| Mockito | Test doubles for constructor-injected dependencies |

## Database

### PostgreSQL
- **Type**: Relational
- **Client**: Spring JdbcTemplate (parameterized queries)
- **Extensions**: pgvector (semantic vector search for memory retrieval)
- **Schemas**: `kukuvaia_agent` (users, sessions, conversations, memories, plans), `kukuvaia_data` (pipeline data)
- **Rationale**: Spring AI JdbcChatMemoryRepository native support, ACID for session persistence, pgvector for semantic search
- **Key Tables**: users, sessions, conversations (JSONB), memories (+ embedding vector), plans

### MongoDB
- **Type**: Document (NoSQL)
- **Client**: MongoDB Java Driver (direct, not Spring Data)
- **Usage**: Read-only access to ETSL outline/content data via MongoTools
- **Rationale**: Legacy data source from sl-content-engine origin

## Build Tools & Package Management
- **Server**: Gradle multi-module (kukuvaia-core, kukuvaia-agents, kukuvaia-memory, kukuvaia-app)
- **CLI**: Go modules (`go.mod`)

## Infrastructure

### Containerization
- Not yet detected — recommended for production deployment

### CI/CD
- Not yet configured — planned (see roadmap)

### LLM Providers
| Provider | Auth | Protocol |
|----------|------|----------|
| GitHub Copilot | OAuth device flow | OpenAI-compatible API |
| SmartGate | JWT | OpenAI-compatible API |

Both providers use Spring AI `OpenAiApi` with different `baseUrl` — switchable at runtime via `/login` and `/model` commands.

## Key Dependencies

### Server
- Spring AI ChatClient + ToolCallAdvisor — automatic multi-round tool calling
- Spring AI MCP Server — @McpTool annotation-based tool registration with auto JSON Schema
- Spring AI MCP Client — connects to external MCP servers (future)
- JdbcChatMemoryRepository — PostgreSQL-backed session persistence
- MessageWindowChatMemory — windowed context compaction
- Embabel Agent Framework — GOAP planning, blackboard state, multi-model LLM, @Agent annotations
- pgvector — semantic vector search for memory retrieval

### CLI
- net/http — standard library HTTP client + SSE consumption
- kukuvaia-theme.yaml — shared design tokens (colors, spacing, borders)

## Design System
Single source of truth: `kukuvaia-theme.yaml` (Matrix/terminal aesthetic — phosphor green #00FF41 on black).
- CLI: Parsed → Lipgloss constants (`go generate`)
- Web (future): Parsed → CSS custom properties (build step)

---
*Last Updated: 2026-04-10 — Added Kotlin, Embabel, pgvector, multi-module Gradle*
*Auto-detected: Languages, frameworks, databases, package structure, testing tools, code patterns*
*User-provided: Project goals, team context*

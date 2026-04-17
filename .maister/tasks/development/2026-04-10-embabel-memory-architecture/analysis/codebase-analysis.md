# Codebase Analysis Report

**Date**: 2026-04-10
**Task**: Document Embabel integration and Memory architecture decisions

## Summary

kukuvaia-engine is a three-module Gradle project (core/agents/app) with a dual-layer memory system in `kukuvaia-core`: Spring AI's `JdbcChatMemoryRepository` handles session-scoped chat history, while custom `agent_memory` table with `MemoryRepository` + `PersistentMemoryAdvisor` + `MemoryTools` provides cross-session persistent knowledge. Embabel integration in `kukuvaia-agents` is minimal — single `PingAgent` verifying framework wiring, no LLM/memory/tool integration yet.

## Module Structure

| Module | Language | Files | LOC | Purpose |
|--------|----------|-------|-----|---------|
| kukuvaia-core | Java | 72 src + 25 test | ~5,000 | Domain logic, tools, API, security |
| kukuvaia-agents | Kotlin | 2 src + 1 test | 28 | Embabel agent orchestration (placeholder) |
| kukuvaia-app | Java | 1 src + 4 resources | 60 | Spring Boot entry point + migrations |

## Dependency Graph

```
kukuvaia-app
├── kukuvaia-core (api) → Spring Boot 3.4.4 + Spring AI 1.1.0
└── kukuvaia-agents (implementation) → kukuvaia-core + Embabel 0.3.4
```

## Memory Architecture (Current)

### Layer 1: Session Chat Memory (Spring AI)
- `JdbcChatMemoryRepository` → `SPRING_AI_CHAT_MEMORY` table (row per message)
- `MessageWindowChatMemory` → 20-message sliding window
- `MessageChatMemoryAdvisor` → injects history (order: HIGHEST_PRECEDENCE+10)

### Layer 2: Persistent Cross-Session Memory (Custom)
- `agent_memory` table: UUID, user_id, category, name, description, content
- `MemoryRepository`: CRUD + FTS (tsvector/GIN)
- `PersistentMemoryAdvisor`: loads ALL user+feedback memories → system prompt (order: HP+5)
- `MemoryTools`: @Tool saveMemory, searchMemories, listMemories, deleteMemory

### Advisor Chain Order
1. ProviderAuditLog (HIGHEST_PRECEDENCE)
2. ToolResultSanitizingAdvisor (HP+1)
3. PersistentMemoryAdvisor (HP+5)
4. MessageChatMemoryAdvisor (HP+10)
5. ToolCallAdvisor (default)

## Embabel Status
- Framework: embabel-agent-starter:0.3.4
- PingAgent.kt: @Agent, @AchievesGoal, @Action — deterministic test only
- No GOAP agents, no LLM calls, no memory/tool integration

## DB Schema
- V1: kukuvaia_agent schema
- V2: agent_memory (UUID, user_id, category CHECK, UNIQUE(user_id,name), GIN FTS)
- V3: agent_plans (session_id PK, task, steps JSONB)
- Spring AI auto: SPRING_AI_CHAT_MEMORY (row per message)

## Key Concerns
1. PersistentMemoryAdvisor loads ALL memories — no relevance filtering
2. Row-per-message storage — millions of rows at scale
3. No session entity — conversation_id has no user linkage
4. No memory lifecycle (decay, compaction, TTL)
5. No semantic/vector search (keyword-only tsvector)
6. PlanningTools lacks user isolation
7. Test coverage: 5 tests / 8 memory classes (critical gaps)

## Complexity: Moderate | Risk: Low

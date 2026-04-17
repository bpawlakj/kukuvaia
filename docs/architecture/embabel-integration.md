# Embabel Integration Architecture

## Decision

Adopt Embabel Agent Framework (Kotlin) as the agent orchestration layer in a dedicated `kukuvaia-agents` module, complementing Spring AI's ChatClient-based conversational loop in `kukuvaia-core`.

## Why Embabel

Spring AI provides the LLM integration layer (ChatClient, advisors, tool calling, memory) but has no built-in agent orchestration — no planning, no goal decomposition, no multi-agent coordination. Embabel fills this gap.

| Concern | Spring AI (kukuvaia-core) | Embabel (kukuvaia-agents) |
|---------|--------------------------|---------------------------|
| LLM conversation | ChatClient + advisors | Not used for chat |
| Tool calling | @McpTool + ToolCallAdvisor | @Action methods |
| Planning | None — developer codes logic | GOAP (A* algorithm, deterministic) |
| Multi-model | Single ChatModel per request | Per-action model selection |
| State management | MessageWindowChatMemory (session) | Blackboard (process-scoped) |
| Sub-agents | SubAgentFactory (manual) | RunSubagent (framework-managed) |

Embabel was chosen over LangChain4j agentic module because:
- GOAP planning is deterministic (explainable, not LLM-hallucinated paths)
- Type-safe domain modeling (Kotlin data classes as action inputs/outputs)
- Created by Rod Johnson (Spring founder) — deep Spring integration
- Builds ON Spring AI (not a replacement)

## Architecture

```
User
  │
  ▼
kukuvaia-core (Java / Spring AI)
  │  ChatClient + Advisors        ← interactive conversation
  │  @McpTool                     ← tool definitions
  │  AgentService                 ← chat orchestration
  │
  │  delegates complex tasks to:
  │
  ▼
kukuvaia-agents (Kotlin / Embabel)
  │  @Agent + @Action + @AchievesGoal
  │  GOAP Planner                 ← deterministic action sequencing
  │  Blackboard                   ← typed state between actions
  │  Multi-model LLM              ← per-action model selection
  │
  │  accesses:
  │
  ▼
kukuvaia-memory (Java)
  │  MemoryRepository             ← persistent cross-session knowledge
  │  SessionRepository            ← session-user linkage
  │  SmartMemoryAdvisor           ← semantic top-K retrieval
```

## Key Concepts

### GOAP (Goal-Oriented Action Planning)

Borrowed from game AI. You define:
- **States** — Kotlin data classes representing intermediate results
- **Actions** — `@Action` methods that transform one state into another
- **Goal** — `@AchievesGoal` marks the terminal action

The GOAP planner uses A* to find the optimal sequence of actions from current state to goal. Planning is deterministic — the LLM is only used inside individual actions, not for deciding the sequence.

```kotlin
// GOAP discovers: UserInput → searchWeb() → WebFindings
//                 UserInput → analyzeDoc() → DocAnalysis
//                 WebFindings + DocAnalysis → compileReport() → Report

@Agent(description = "Research agent")
class ResearchAgent {
    @Action
    fun searchWeb(input: UserInput, ctx: OperationContext): WebFindings { ... }

    @Action
    fun analyzeDoc(input: UserInput, ctx: OperationContext): DocAnalysis { ... }

    @AchievesGoal(description = "Research report compiled")
    @Action
    fun compileReport(web: WebFindings, doc: DocAnalysis, ctx: OperationContext): Report { ... }
}
```

### Blackboard Pattern

Shared memory for a single agent process. Actions read inputs from the blackboard by type and write outputs back automatically. No manual state threading.

```
[UserInput] → blackboard → searchWeb() → [WebFindings] → blackboard
                          → analyzeDoc() → [DocAnalysis] → blackboard
                          → compileReport() reads both → [Report]
```

### Multi-Model LLM

Different actions can use different models for cost optimization:

```kotlin
@Action
fun draft(input: UserInput, ctx: OperationContext): Draft =
    ctx.ai().withLlm(LlmOptions.cheap()).create("Draft: ${input.content}")

@Action
fun review(draft: Draft, ctx: OperationContext): ReviewedDraft =
    ctx.ai().withLlm(LlmOptions.powerful()).generateText("Review: ${draft.text}")
```

### @Condition (Dynamic Replanning)

Conditions are re-evaluated after each action. If a condition changes, GOAP replans:

```kotlin
@Condition("User has been verified")
fun isVerified(ctx: OperationContext): Boolean =
    ctx.blackboard.has(VerifiedUser::class)
```

## Integration with kukuvaia-core

Embabel agents access core services via Spring DI:

| Core Bean | How Embabel Uses It |
|-----------|---------------------|
| `MemoryRepository` | Persist/retrieve cross-session knowledge |
| `LlmProviderService` | Resolve LLM provider (Copilot, SmartGate) |
| `ToolRegistryConfig` | Enumerate available tools by name |
| `SubAgentFactory` | Spawn isolated specialist agents |
| `ToolLoader` | Access extension tools from `.kukuvaia/tools/` |

```kotlin
@Agent(description = "Agent using core services")
class CoreIntegratedAgent(
    private val memoryRepository: MemoryRepository,  // DI from core
    private val toolRegistry: ToolRegistryConfig,
) {
    @Action
    fun research(input: UserInput, ctx: OperationContext): Findings {
        val related = memoryRepository.search("agent", input.content)
        // ... use Embabel AI + core memory
    }
}
```

## Module Structure

```
kukuvaia-agents/
├── build.gradle
│   ├── depends on :kukuvaia-core
│   ├── depends on :kukuvaia-memory
│   ├── embabel-agent-starter:0.3.4
│   └── kotlin-stdlib + kotlin-reflect
└── src/main/kotlin/ai/kukuvaia/agents/
    ├── PingAgent.kt              ← framework wiring test (exists)
    ├── ResearchAgent.kt          ← web + doc analysis (planned)
    ├── ValidationAgent.kt        ← multi-step validation (planned)
    └── ...
```

## Embabel Capabilities Beyond Planning

| Feature | Description | Relevance for kukuvaia |
|---------|-------------|------------------------|
| MCP Server export | Agents as MCP tools via SSE/HTTP | Agents callable from external systems |
| A2A Protocol | Agent-to-agent communication | Cross-platform agent orchestration |
| RAG (pgvector) | Built-in vector search modules | Complements kukuvaia-memory |
| Human-in-the-Loop | WaitFor pattern, WAITING state | Interactive approval flows |
| Autonomy (Open mode) | LLM composes agent from available actions | Most advanced — LLM-driven composition |
| Testing | FakeOperationContext, FakePromptRunner | Unit tests without API keys |

## Current Status

- **Framework**: embabel-agent-starter:0.3.4 — imported, compiles, tests pass
- **PingAgent**: Deterministic test agent — verifies @Agent/@Action/@AchievesGoal wiring
- **Production agents**: None yet — planned for after kukuvaia-memory module

## Relationship to Spring AI ChatClient

Embabel does NOT replace ChatClient. They serve different purposes:

```
Interactive chat (user types → LLM responds → tools called → repeat):
  → Spring AI ChatClient + Advisors (kukuvaia-core)

Complex autonomous tasks (goal → plan → execute actions → result):
  → Embabel GOAP Agents (kukuvaia-agents)

Both share:
  → kukuvaia-memory (persistent knowledge)
  → LLM providers (via LlmProviderService)
  → Tools (via ToolRegistryConfig)
```

## Version

- Embabel: 0.3.4 (active development, weekly releases)
- Kotlin: 2.1.20
- Spring Boot compatibility: 3.4.4+ (Embabel builds on Spring AI)

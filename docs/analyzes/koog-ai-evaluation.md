# Koog AI Evaluation — Should kukuvaia adopt it?

**Date:** 2026-04-15
**Decision:** Do NOT add Koog as dependency. Selectively adopt ideas into existing Spring AI stack.

## What is Koog AI

Koog is JetBrains' open-source framework for building AI agents on the JVM. Kotlin DSL + Java API. Key features:

| Capability | Koog | Kukuvaia (existing) |
|---|---|---|
| Agent loop + tool calling | AIAgent + ToolRegistry | Spring AI ChatClient + ToolCallAdvisor |
| GOAP planning | A* search with belief/execution separation | Embabel 0.3.4 (GOAP with Blackboard) |
| Tool registration | Annotation-based + class-based + ToolRegistry | @McpTool (auto JSON Schema) |
| Memory (chat/agent/long-term) | ChatMemory + AgentMemory + VectorDB RAG | kukuvaia-memory (3 types, pgvector, JSONB) |
| MCP support | Client only | Server + Client (Spring AI) |
| LLM provider routing | Multi-provider support | LlmProviderService (Copilot/SmartGate) |
| Graph-based workflows | Typed nodes, conditional edges, subgraphs | Advisor chain + WorkflowRegistry + PipelineEngine + Embabel GOAP |
| Streaming | Supported | Flux\<ChatResponse\> SSE |
| OpenTelemetry | Built-in with hierarchical spans | Not yet (P01 plan ready) |
| History compression | 4 strategies (WholeHistory, FromLastNMessages, Chunked, RetrieveFactsFromHistory) | 3-layer compaction + P04 plan (rolling summary, not yet implemented) |

## Why NOT add Koog

1. **~100% functional overlap** with Spring AI + Embabel. Adding Koog means two frameworks doing the same job.
2. **Embabel already provides GOAP** — migrating to Koog GOAP is high cost, zero functional gain.
3. **Koog is MCP Client only** — kukuvaia needs MCP Server (@McpTool). Spring AI provides both.
4. **Mixing orchestration models** — Koog has its own executor/strategy model vs. Spring AI's advisor chain. Two competing models in one server = architectural confusion.
5. **Koog is new and immature** — just released by JetBrains. Spring AI has established ecosystem, monthly releases, VMware/Broadcom backing.

## What IS worth adopting (ideas, not library)

### 1. Graph-Based Workflows → SKIP
Kukuvaia covers all patterns via 4 complementary mechanisms:
- Linear advisor chain (10 advisors, ordered precedence)
- Conditional branching (IntentDetectionAdvisor → ModelRoutingAdvisor + LoopDetectionAdvisor)
- Parallel fan-out/fan-in (SubAgentFactory.executeParallel())
- Multi-step planning with replanning (Embabel GOAP)
- Fixed step sequences (PipelineEngine + WorkflowRegistry)

No concrete use case requires a fifth orchestration mechanism.

### 2. OpenTelemetry Per-Agent-Run Tracing → ADAPT
Koog's insight: hierarchical spans with conversation-level trace IDs (CreateAgentSpan → InvokeAgentSpan → StrategySpan → NodeExecuteSpan → InferenceSpan → ExecuteToolSpan) and koog-specific attributes.

**Action:** Enhance P01 with two additions:
- **Enhancement A:** Session-scoped span attributes (kukuvaia.session.id, kukuvaia.provider on all spans)
- **Enhancement B:** OTel context propagation in SubAgentFactory async tasks (parent-child span relationships preserved)

See updated `docs/work/001-observability/plan.md` for details.

### 3. History Compression Strategies → ADAPT
Koog has 4 strategies: WholeHistory, FromLastNMessages(N), Chunked(N), RetrieveFactsFromHistory.

Kukuvaia already covers 3 of 4:
- FromLastNMessages → MessageWindowChatMemory (N=20) ✅
- RetrieveFactsFromHistory → MemoryExtractionService + SmartMemoryAdvisor ✅
- WholeHistory → P04 ConversationSummarizationService (rolling summary) — designed, not implemented
- Chunked → Not needed (rolling summary is superior for our use case)

**Action:** Implement P04 as designed + add adaptive summarization interval based on session length.

See updated `docs/work/005-conversation-summarization/plan.md` for details.

## Implementation Priority

| # | Task | Effort | Rationale |
|---|------|--------|-----------|
| 1 | P01 + OTel enhancements | ~10h | Foundation — all subsequent work benefits from observability |
| 2 | P04 + adaptive interval | ~8h | User-facing value — solves context amnesia after 20 messages |

## Koog Features Not Relevant to kukuvaia

| Feature | Why not |
|---|---|
| Kotlin Multiplatform (JS, WasmJS, Android, iOS) | kukuvaia-engine is server-side Java |
| ReAct strategy | Spring AI ToolCallAdvisor already implements reason-act loop |
| Ktor plugin | kukuvaia uses Spring Boot |
| A2A Protocol | Not needed at current scale |
| Koog's built-in Langfuse/W&B exporters | Not needed as Koog-specific plugins — kukuvaia uses standard OTel OTLP which both Langfuse and Grafana Tempo ingest natively. Langfuse **is** adopted as a complementary backend (see P01 "Langfuse Integration" section) for LLM-specific prompt/response UI alongside Jaeger/Tempo for infra traces. |

## References

- Koog AI documentation: https://docs.koog.ai/
- Spring AI integration page: https://docs.koog.ai/spring-ai-integration/
- P01 observability plan: `docs/work/001-observability/plan.md`
- P04 conversation summarization plan: `docs/work/005-conversation-summarization/plan.md`

# Chat Flow — Class-Level Call Sequence

How a single chat request travels through the kukuvaia-engine classes, from the HTTP endpoint to the LLM and back.

## Layer view (top → bottom)

```
HTTP POST /api/chat   (Spring Web / SSE)
        │
        ▼
ChatController.chat()
        │ delegates to
        ▼
CommandRouter.route(message, sessionId)
        │
        ├── starts with "/"? ─yes──▶ CommandRegistry / SkillRegistry / handlePlanCommand()
        ├── planning session? ─yes──▶ handlePlanningMessage() → PlanningModeService (phase transition)
        └── else ──────────────────▶ AgentService.streamChat()
                                            │
                                            ▼
                                    AgentService.streamChat(sessionId, message)
                                            │
                                            ├─▶ SessionRepository.ensureExists()   [PG upsert]
                                            ├─▶ PersonaService.getActivePersona()
                                            ├─▶ PlanningTools.setContext()         [ThreadLocal]
                                            │
                                            └─▶ chatClient.prompt()…call().content()
                                                      │
                                                      ▼
                                                === ADVISOR CHAIN ===
                                                (ordered by @Order / Ordered)
```

## Advisor chain (inside `ChatClient.call()`)

Defined in `config/ChatClientConfig.java`, ordered by Spring `Ordered`:

| # | Advisor | Role |
|---|---------|------|
| 1 | `ProviderAuditLog` | Log provider/model selection (`HIGHEST_PRECEDENCE`) |
| 2 | `ModelRoutingAdvisor` | Pick model tier (`HP + 12`) |
| 3 | `LoopDetectionAdvisor` | Detect repeated tool-call loops (`HP + 13`) |
| 4 | `DataMaskingAdvisor` | Mask PII before it reaches the LLM |
| 5 | `HarnessAdvisor` | Inject user-defined rule bundles |
| 6 | `PlanningModeService` | Inject phase-specific prompt + filter tools per phase (`HP + 15`) |
| 7 | `ToolResultSanitizingAdvisor` | Sanitize tool outputs before reinjection |
| 8 | `SmartMemoryAdvisor` | Top-K semantic retrieval from pgvector (`HP + 5`) |
| 9 | `MessageChatMemoryAdvisor` | Inject conversation history (JDBC chat memory) |
| 10 | `ToolCallAdvisor` | **Agent loop** — LLM ↔ tools until convergence |

## Tool-call loop (inside `ToolCallAdvisor`)

```
loop:
  → Call LLM with current messages
  ← Response:
        (a) plain text         → done
        (b) tool_call(s)
  → ToolCallingManager dispatches to @Tool methods:
        PlanningTools.{createPlan, updateDiscoveryFacts, completeStep, revisePlan}
        MemoryTools.{saveMemory, searchMemories, listMemories, deleteMemory}
        DelegationTools.{delegateToSubAgent, …}
        + any MCP tools (if external MCP servers are wired)
  → Append tool result as ToolResponseMessage
  → Next iteration
max iterations = kukuvaia.max-tool-rounds (default 20)
```

## After the content arrives

```
AgentService.streamChat() (continued)
        │
        ├─▶ PlanningTools.clearContext()    [ThreadLocal cleanup in a finally block]
        ├─▶ triggerExtraction()             [async on memoryExtractionExecutor]
        │     └─▶ MemoryExtractionService.extract(userId, sessionId)
        │           └─▶ LLM classifier → kukuvaia.memories (pgvector embedding)
        │
        └─▶ return Flux.just(new TextBlock(response, null))
                    │
                    ▼
            ChatController applies .timeout() + .onErrorResume()
                    │
                    ▼
            SSE stream → HTTP response → CLI / admin client
```

## Timeline for `"hej"` (free text, no planning)

1. `ChatController.chat()` — HTTP receives `ChatRequest{sessionId, message}`.
2. `CommandRouter.route()` — no slash, no planning → falls through to `AgentService`.
3. `AgentService.streamChat()`:
   - `SessionRepository.ensureExists(sessionId, "bartek")`
   - `PersonaService.getActivePersona(sessionId)` → `PersonaSpec` (default)
   - `PlanningTools.setContext(sessionId, "bartek")` (ThreadLocal)
4. `ChatClient.prompt().system(persona.systemPrompt()).user("hej").call()`.
5. Advisor chain fires (relevant for plain chat: `ProviderAuditLog` → `ModelRouting` → `SmartMemory` → `MessageChatMemory` → `ToolCall`).
6. `ToolCallAdvisor` — first LLM call; model returns plain text. No tool calls.
7. Content returned to `AgentService`.
8. `MemoryExtractionService` starts asynchronously (in the background) — classifies messages for long-term memory.
9. `AgentService` returns `Flux<OutputBlock>` with a single `TextBlock`.
10. `ChatController` streams the SSE response to the client.

## Timeline for `/plan trip to Crete, we go by our own car`

1. `ChatController` → `CommandRouter.route()`.
2. `CommandRouter` detects `/plan` → `handlePlanCommand(args, sessionId)`.
3. `PlanningModeService.startPlanning(sessionId, task)`:
   - `extractFactsOrEmpty(task)` — **extra synchronous LLM call** (`ChatClient.create(chatModel)` with structured output `.entity(DiscoveryFacts.class)`).
   - Result example: `known=[Crete, own car]`, `excluded=[flights, train, car rental]`, `gaps=[dates, travelers, budget]`.
   - `sessions.put(sessionId, new PlanningSession(...))`.
4. `AgentService.streamChat(sessionId, task)` (normal flow, **but**):
5. In the advisor chain, `PlanningModeService.before()` sees the session is in DISCOVERY:
   - Injects the **phase prompt** with the CURRENT STATE block (Known / Excluded / Gaps / Ambiguities).
   - Filters the tool list: removes `createPlan`, `revisePlan`, `completeStep` (BLOCKED_IN_DISCOVERY).
6. `ToolCallAdvisor` runs — the LLM asks only about gaps, never about excluded options.
7. The LLM calls `updateDiscoveryFacts(...)` (a `PlanningTools` `@Tool`) → `PlanningModeService.updateFacts()` updates the `ConcurrentHashMap`.
8. Response content flows back — user sees clarifying questions.
9. On the next user turn ("ready" / "gotowe") → `CommandRouter.handlePlanningMessage()` → `READY_TRIGGERS.contains("gotowe")` → `advanceToDrafting()`.
10. Next `streamChat`: advisor injects the DRAFTING prompt (Known + Excluded as hard constraints, `createPlan` is now allowed).
11. The LLM calls `createPlan()` → insert into `kukuvaia.plans` with `status='draft'` → `advanceToApproval()`.
12. User clicks "Approve plan" in the CLI → CLI sends a trigger (`"tak"` / `"zatwierdź"` / `"approve"`) → `CommandRouter` → `APPROVAL_TRIGGERS.contains(...)` → `approvePlan()` → UPDATE `status='active'`, remove from in-memory sessions.

## Key synchronization points

- **ThreadLocal (`PlanningTools.SESSION_ID`, `PlanningTools.USER_ID`)** — `@Tool` methods are invoked by Spring AI without access to the Reactor context. Set before `chatClient.call()`, cleared in `finally`.
- **`ConcurrentHashMap<String, PlanningSession>`** — in-memory state for planning mode; lost on restart (no TTL, known debt — tracked in the roadmap).
- **Spring AI `MessageWindowChatMemory` + `JdbcChatMemoryRepository`** — conversation persistence in `kukuvaia.spring_ai_chat_memory`.
- **`SmartMemoryAdvisor`** reads from `kukuvaia.memories` (pgvector) and appends top-K relevant entries into the prompt.

## Where to look in the code

| Layer | File |
|-------|------|
| Controller | `api/ChatController.java` |
| Routing | `agent/CommandRouter.java` |
| Orchestration | `agent/AgentService.java` |
| Personas | `agent/PersonaService.java` |
| Planning state | `agent/PlanningModeService.java` + `PlanningSession` + `DiscoveryFacts` |
| Tools | `tools/PlanningTools.java`, `tools/MemoryTools.java`, `tools/DelegationTools.java` |
| Chain configuration | `config/ChatClientConfig.java` |
| Memory advisor | `memory/advisor/SmartMemoryAdvisor.java` |
| Memory extraction | `memory/extraction/MemoryExtractionService.java` |
| Session DB access | `memory/repository/SessionRepository.java` |

## Related documentation

- `docs/architecture/agent-orchestration-decisions.md` — sub-agent design, daemon mode, Spring AI validation.
- `docs/architecture/memory-architecture.md` — memory subsystem (episodic / semantic / procedural, pgvector).
- `docs/architecture/embabel-integration.md` — Kotlin/GOAP agent module that can plug into this flow.

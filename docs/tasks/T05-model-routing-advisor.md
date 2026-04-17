# T05: Interactive Chat Model Routing

**Status**: `pending`
**Tier**: 2 — Capabilities
**Depends On**: T02
**Blocks**: T06, T12
**Source**: `docs/plan/model-routing-and-embabel.md` Phase 5

## Goal

Spring AI advisors that route interactive chat to appropriate model tier based on complexity, and detect tool-call loops with automatic escalation.

## Scope

### ModelRoutingAdvisor
- `BaseAdvisor` at order `HIGHEST_PRECEDENCE + 12`
- **before()**: classify complexity → override model via `OpenAiChatOptions`
  - `FAST`: short messages, greetings → worker role (Haiku)
  - `DEFAULT`: normal tasks → default role (Sonnet)
  - `ESCALATE`: loop flag, explicit request → advisor role (Opus)
- **after()**: log routing decision for audit

### LoopDetectionAdvisor
- `BaseAdvisor` tracking per-session tool-call rounds
- `ConcurrentHashMap<String, SessionToolState>`
- Warning at 5 rounds: inject "wrap up" hint
- Escalation at 8 rounds: set flag → ModelRoutingAdvisor switches tier
- Hard abort at 15 rounds: return error, reset state
- Reset on response without tool calls

### IntentDetectionAdvisor Enhancement
- Add `SIMPLE_CONVERSATION` intent for greetings/trivial questions
- Helps ModelRoutingAdvisor classify more accurately

### Configuration
```yaml
kukuvaia:
  routing:
    loop-warning-rounds: 5
    loop-escalation-rounds: 8
    loop-abort-rounds: 15
```

## File Inventory

### Create
- `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/ModelRoutingAdvisor.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/LoopDetectionAdvisor.java`

### Modify
- `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java` — add advisors to chain
- `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/IntentDetectionAdvisor.java` — add SIMPLE_CONVERSATION

## Acceptance Criteria

- [ ] "hi" routes to worker/Haiku (visible in logs)
- [ ] "analyze this document in detail" routes to default/Sonnet
- [ ] Loop flag triggers escalation to advisor/Opus
- [ ] Hard abort after 15 tool rounds returns error message
- [ ] State resets after successful (no-tool) response
- [ ] Unit tests for classification logic and loop thresholds

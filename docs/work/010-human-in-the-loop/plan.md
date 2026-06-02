# P09: Human-in-the-Loop — Approval Workflows and Confidence Thresholds

**Created**: 2026-04-10
**Status**: Draft
**Module**: kukuvaia-core (advisor, output, API), kukuvaia-cli (approval UI)
**Depends on**: ChatClientConfig, OutputBlock, ChatController, AgentService

---

## Problem

Kukuvaia's agent executes all tool calls automatically via Spring AI's tool calling loop. There is no mechanism to pause execution, present a proposed action to the user, and wait for approval before proceeding. This is a safety concern for:

- **Destructive operations** — tools that delete data, modify files, or call external APIs with side effects
- **High-cost actions** — LLM calls to expensive models or bulk operations
- **Uncertain decisions** — when the LLM's tool call arguments look suspicious or the confidence is low
- **Compliance requirements** — regulated environments require human approval for certain actions
- **Daemon tasks** — background tasks that currently run unsupervised

The current system has DaemonScheduleGuard (prevents overlapping cron jobs) and SubAgentGuard (depth limit, tool filtering), but neither involves human judgment at decision points.

---

## Current State

| Component | What it provides | What it lacks |
|-----------|-----------------|---------------|
| Spring AI ToolCallAdvisor | Automatic multi-round tool execution loop | No pause/approval mechanism |
| SubAgentGuard | Depth limit (max 1), tool filtering | No human checkpoint |
| DaemonScheduleGuard | Prevents overlapping execution | No approval for high-risk actions |
| ChatController | SSE stream of OutputBlock | No bidirectional approval protocol |
| CLI | Renders OutputBlocks, sends messages | No approval prompt UI |

### How Spring AI tool calling works today

```
User message → ChatClient → LLM
  LLM returns: tool_call(name="delete_item", args={id: 123})
  Spring AI automatically: execute tool → return result → send back to LLM
  LLM returns: "I deleted item 123"
```

There is no interception point between "LLM decides to call a tool" and "tool is executed."

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    ToolApprovalAdvisor                            │
│               (intercepts tool calls)                            │
│                                                                   │
│  Tool call requested by LLM                                      │
│    │                                                              │
│    ▼                                                              │
│  RiskClassifier.classify(toolName, args)                         │
│    │                                                              │
│    ├── LOW (read-only): auto-approve                             │
│    ├── MEDIUM (write): check approval mode                       │
│    │     ├── auto-approve mode → execute                         │
│    │     └── prompt mode → pause, ask user                       │
│    └── HIGH (bash, delete, external API): require approval       │
│          └── always pause, ask user                              │
│                                                                   │
│  Approval flow (MEDIUM prompt / HIGH always):                    │
│    ├── Emit ApprovalRequestBlock via SSE                         │
│    │     { toolName, args, riskLevel, reason, requestId }        │
│    ├── Wait for user response (with timeout)                     │
│    │     POST /api/approval/{requestId}                          │
│    │     { decision: "approve" | "reject" | "modify", ... }     │
│    ├── Approved → execute tool, continue LLM loop                │
│    ├── Rejected → return rejection to LLM as tool result         │
│    └── Timeout → auto-reject, inform LLM                        │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                         CLI (Go)                                  │
│                                                                   │
│  Receives ApprovalRequestBlock via SSE                           │
│    ├── Renders approval prompt with tool details                 │
│    │   ┌──────────────────────────────────────┐                  │
│    │   │ ⚠ APPROVAL REQUIRED                  │                  │
│    │   │ Tool: delete_item                     │                  │
│    │   │ Args: {"id": 123, "name": "draft"}    │                │
│    │   │ Risk: HIGH (destructive operation)    │                  │
│    │   │                                       │                  │
│    │   │ [A]pprove  [R]eject  [M]odify         │                │
│    │   └──────────────────────────────────────┘                  │
│    └── Sends decision to POST /api/approval/{requestId}          │
└──────────────────────────────────────────────────────────────────┘
```

---

## Implementation

### Step 1: Risk classification

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/approval/RiskLevel.java`

```java
package ai.kukuvaia.approval;

public enum RiskLevel { LOW, MEDIUM, HIGH }
```

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/approval/RiskClassifier.java`

```java
package ai.kukuvaia.approval;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

/**
 * Classifies tool calls by risk level.
 *
 * Classification is based on tool name patterns and argument analysis.
 * Configurable via application.yaml overrides.
 */
@Component
public class RiskClassifier {

    // Default risk classification by tool name pattern
    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
            "bash_run", "delete_item", "delete_memory", "drop_collection",
            "execute_command", "send_email", "send_notification",
            "update_production", "deploy"
    );

    private static final Set<String> MEDIUM_RISK_TOOLS = Set.of(
            "write_file", "create_plan", "revise_plan", "save_memory",
            "update_item", "modify_document", "run_validation"
    );

    // Everything else is LOW (read-only tools: search, list, get, read)

    private final ApprovalConfig config;

    public RiskClassifier(ApprovalConfig config) {
        this.config = config;
    }

    /**
     * Classify a tool call by risk level.
     */
    public RiskAssessment classify(String toolName, Map<String, Object> arguments) {
        // Check config overrides first
        RiskLevel override = config.toolOverrides().get(toolName);
        if (override != null) {
            return new RiskAssessment(override, "configured override for " + toolName);
        }

        // Pattern-based classification
        if (HIGH_RISK_TOOLS.contains(toolName) || toolName.startsWith("delete_")) {
            return new RiskAssessment(RiskLevel.HIGH,
                    "destructive or external operation: " + toolName);
        }

        if (MEDIUM_RISK_TOOLS.contains(toolName) || toolName.startsWith("write_")
                || toolName.startsWith("update_") || toolName.startsWith("create_")) {
            return new RiskAssessment(RiskLevel.MEDIUM,
                    "write operation: " + toolName);
        }

        return new RiskAssessment(RiskLevel.LOW, "read-only operation");
    }

    public record RiskAssessment(RiskLevel level, String reason) {}
}
```

### Step 2: ApprovalRequestBlock (new OutputBlock type)

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/output/OutputBlock.java`

```java
public sealed interface OutputBlock permits
        TextBlock, TableBlock, CodeBlock, ProgressBlock,
        PlanBlock, VerificationBlock, MetadataBlock,
        ApprovalRequestBlock {
}
```

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/output/ApprovalRequestBlock.java`

```java
package ai.kukuvaia.output;

import ai.kukuvaia.approval.RiskLevel;
import java.util.Map;

/**
 * Requests human approval for a tool call.
 * CLI renders this as an interactive approval prompt.
 * User responds via POST /api/approval/{requestId}.
 */
public record ApprovalRequestBlock(
        String requestId,              // UUID for correlation
        String toolName,               // e.g., "delete_item"
        Map<String, Object> arguments, // tool call arguments
        RiskLevel riskLevel,           // LOW, MEDIUM, HIGH
        String reason,                 // human-readable justification
        int timeoutSeconds             // auto-reject after this many seconds
) implements OutputBlock {
}
```

### Step 3: Approval store (in-memory pending approvals)

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalStore.java`

```java
package ai.kukuvaia.approval;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stores pending approval requests and their corresponding futures.
 * Thread-safe. Entries are auto-cleaned after timeout.
 */
@Component
public class ApprovalStore {

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    public record PendingApproval(
            String requestId,
            String toolName,
            Map<String, Object> arguments,
            CompletableFuture<ApprovalDecision> future,
            long createdAt
    ) {}

    /**
     * Create a pending approval request. Returns a future that completes
     * when the user responds or the timeout expires.
     */
    public CompletableFuture<ApprovalDecision> createRequest(
            String requestId, String toolName, Map<String, Object> arguments) {

        var future = new CompletableFuture<ApprovalDecision>();
        pending.put(requestId, new PendingApproval(
                requestId, toolName, arguments, future, System.currentTimeMillis()));
        return future;
    }

    /**
     * Resolve a pending request with the user's decision.
     */
    public boolean resolve(String requestId, ApprovalDecision decision) {
        var entry = pending.remove(requestId);
        if (entry == null) return false;
        entry.future().complete(decision);
        return true;
    }

    /**
     * Wait for approval with timeout. Returns REJECTED on timeout.
     */
    public ApprovalDecision awaitApproval(String requestId, int timeoutSeconds)
            throws InterruptedException {
        var entry = pending.get(requestId);
        if (entry == null) return ApprovalDecision.rejected("Request not found");

        try {
            return entry.future().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(requestId);
            return ApprovalDecision.rejected("Approval timed out after " + timeoutSeconds + "s");
        } catch (Exception e) {
            pending.remove(requestId);
            return ApprovalDecision.rejected("Approval failed: " + e.getMessage());
        }
    }
}
```

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalDecision.java`

```java
package ai.kukuvaia.approval;

/**
 * User's decision on an approval request.
 */
public record ApprovalDecision(
        Decision decision,
        String reason,
        java.util.Map<String, Object> modifiedArgs  // null unless MODIFY
) {
    public enum Decision { APPROVE, REJECT, MODIFY }

    public static ApprovalDecision approved() {
        return new ApprovalDecision(Decision.APPROVE, null, null);
    }

    public static ApprovalDecision rejected(String reason) {
        return new ApprovalDecision(Decision.REJECT, reason, null);
    }

    public static ApprovalDecision modified(java.util.Map<String, Object> newArgs) {
        return new ApprovalDecision(Decision.MODIFY, "User modified arguments", newArgs);
    }

    public boolean isApproved() {
        return decision == Decision.APPROVE || decision == Decision.MODIFY;
    }
}
```

### Step 4: ToolApprovalAdvisor

This is the core component. It wraps the tool execution mechanism to intercept calls that need approval.

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ToolApprovalAdvisor.java`

```java
package ai.kukuvaia.approval;

import ai.kukuvaia.output.ApprovalRequestBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Intercepts tool calls and enforces approval workflows based on risk level.
 *
 * Integration approach: wraps ToolCallbacks with approval-aware versions
 * that check risk level before executing the actual tool.
 *
 * This advisor works by wrapping the registered tool callbacks with
 * proxies that pause execution for approval when needed.
 */
@Component
public class ToolApprovalAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalAdvisor.class);

    private final RiskClassifier riskClassifier;
    private final ApprovalStore approvalStore;
    private final ApprovalConfig config;

    // Callback to emit approval blocks via SSE (set by ChatController)
    private Consumer<ApprovalRequestBlock> approvalEmitter;

    @Override
    public int getOrder() {
        // Run late — after all prompt construction, before ToolHookDispatcher
        return Ordered.LOWEST_PRECEDENCE - 20;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!config.enabled()) return request;

        // Wrap tool callbacks with approval-aware proxies
        // This modifies the tool execution behavior without changing
        // the ChatClient or ToolCallAdvisor configuration
        return request; // Tool wrapping happens at callback level (see Step 5)
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * Called by the approval-aware tool callback proxy.
     * Determines if approval is needed and handles the approval flow.
     *
     * @return true if execution should proceed, false if rejected
     */
    public boolean requestApprovalIfNeeded(String toolName, Map<String, Object> arguments) {
        if (!config.enabled()) return true;

        var assessment = riskClassifier.classify(toolName, arguments);

        return switch (assessment.level()) {
            case LOW -> {
                log.debug("Auto-approved LOW risk tool: {}", toolName);
                yield true;
            }
            case MEDIUM -> {
                if (config.autoApproveMedium()) {
                    log.debug("Auto-approved MEDIUM risk tool (config): {}", toolName);
                    yield true;
                }
                yield requestHumanApproval(toolName, arguments, assessment);
            }
            case HIGH -> requestHumanApproval(toolName, arguments, assessment);
        };
    }

    private boolean requestHumanApproval(String toolName, Map<String, Object> arguments,
                                          RiskClassifier.RiskAssessment assessment) {
        String requestId = UUID.randomUUID().toString();
        int timeout = config.timeoutSeconds();

        // Create pending approval
        approvalStore.createRequest(requestId, toolName, arguments);

        // Emit approval request to CLI via SSE
        var block = new ApprovalRequestBlock(
                requestId, toolName, arguments,
                assessment.level(), assessment.reason(), timeout);

        if (approvalEmitter != null) {
            approvalEmitter.accept(block);
        }

        log.info("Approval requested: id={}, tool={}, risk={}", requestId, toolName, assessment.level());

        // Block thread until user responds or timeout
        try {
            ApprovalDecision decision = approvalStore.awaitApproval(requestId, timeout);
            log.info("Approval decision: id={}, decision={}", requestId, decision.decision());
            return decision.isApproved();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Approval interrupted for tool {}", toolName);
            return false;
        }
    }

    public void setApprovalEmitter(Consumer<ApprovalRequestBlock> emitter) {
        this.approvalEmitter = emitter;
    }
}
```

### Step 5: Approval-aware tool callback wrapper

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalAwareToolCallback.java`

```java
package ai.kukuvaia.approval;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Wraps a ToolCallback with approval logic.
 * Delegates to ToolApprovalAdvisor before executing the actual tool.
 */
public class ApprovalAwareToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolApprovalAdvisor approvalAdvisor;

    public ApprovalAwareToolCallback(ToolCallback delegate, ToolApprovalAdvisor approvalAdvisor) {
        this.delegate = delegate;
        this.approvalAdvisor = approvalAdvisor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();

        // Parse arguments for risk classification
        Map<String, Object> args = parseArguments(toolInput);

        boolean approved = approvalAdvisor.requestApprovalIfNeeded(toolName, args);
        if (!approved) {
            return "{\"error\": \"Tool call rejected by user. Reason: approval denied or timed out. " +
                   "Do NOT retry this tool call. Inform the user that the action was not performed.\"}";
        }

        return delegate.call(toolInput);
    }

    private Map<String, Object> parseArguments(String toolInput) {
        // Parse JSON tool input to extract arguments for risk assessment
        // Use ObjectMapper for JSON parsing
    }
}
```

### Step 6: Approval REST endpoint

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/api/ApprovalController.java`

```java
package ai.kukuvaia.api;

import ai.kukuvaia.approval.ApprovalDecision;
import ai.kukuvaia.approval.ApprovalStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint for CLI to submit approval decisions.
 */
@RestController
@RequestMapping("/api/approval")
public class ApprovalController {

    private final ApprovalStore approvalStore;

    public ApprovalController(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    @PostMapping("/{requestId}")
    public ResponseEntity<Map<String, String>> submitDecision(
            @PathVariable String requestId,
            @RequestBody ApprovalRequest request) {

        ApprovalDecision decision = switch (request.decision()) {
            case "approve" -> ApprovalDecision.approved();
            case "reject" -> ApprovalDecision.rejected(
                    request.reason() != null ? request.reason() : "User rejected");
            case "modify" -> ApprovalDecision.modified(request.modifiedArgs());
            default -> ApprovalDecision.rejected("Unknown decision: " + request.decision());
        };

        boolean resolved = approvalStore.resolve(requestId, decision);
        if (!resolved) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("status", "resolved", "decision", decision.decision().name()));
    }

    public record ApprovalRequest(
            String decision,            // "approve", "reject", "modify"
            String reason,              // optional rejection reason
            Map<String, Object> modifiedArgs  // optional modified arguments
    ) {}
}
```

### Step 7: ApprovalConfig

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalConfig.java`

```java
@ConfigurationProperties(prefix = "kukuvaia.approval")
public record ApprovalConfig(
        boolean enabled,
        boolean autoApproveMedium,       // auto-approve MEDIUM risk tools
        int timeoutSeconds,              // timeout before auto-reject
        Map<String, RiskLevel> toolOverrides  // per-tool risk level overrides
) {
    public ApprovalConfig {
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
        if (toolOverrides == null) toolOverrides = Map.of();
    }
}
```

### Step 8: Wire into ToolRegistryConfig

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/config/ToolRegistryConfig.java`

When approval is enabled, wrap all registered ToolCallbacks with `ApprovalAwareToolCallback`:

```java
public ToolRegistryConfig(Collection<ToolCallbackProvider> providers,
                           ToolApprovalAdvisor approvalAdvisor,
                           ApprovalConfig approvalConfig) {
    for (ToolCallbackProvider provider : providers) {
        for (ToolCallback callback : provider.getToolCallbacks()) {
            ToolCallback effective = approvalConfig.enabled()
                    ? new ApprovalAwareToolCallback(callback, approvalAdvisor)
                    : callback;
            toolsByName.put(callback.getToolDefinition().name(), effective);
        }
    }
}
```

### Step 9: ChatController SSE approval integration

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/api/ChatController.java`

The approval emitter needs to push `ApprovalRequestBlock` into the SSE stream. Since the chat endpoint returns `Flux<OutputBlock>`, approval requests are injected into the same stream:

```java
@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<OutputBlock> chat(@RequestBody ChatRequest request) {
    // Create a Sinks.Many to bridge approval requests into the SSE stream
    var approvalSink = Sinks.many().multicast().<OutputBlock>onBackpressureBuffer();

    // Set the approval emitter on the advisor
    toolApprovalAdvisor.setApprovalEmitter(block -> approvalSink.tryEmitNext(block));

    Flux<OutputBlock> chatStream = commandRouter.route(request.message(), request.sessionId());
    Flux<OutputBlock> approvalStream = approvalSink.asFlux();

    return Flux.merge(chatStream, approvalStream)
            .doFinally(signal -> approvalSink.tryEmitComplete())
            .onErrorResume(e -> {
                log.error("SSE stream error: {}", e.getMessage());
                return Flux.just(new TextBlock("Error: " + e.getMessage(), "error"));
            });
}
```

### Step 10: CLI approval rendering (Go)

**File**: `kukuvaia-cli/internal/tui/approval.go` (conceptual — Go implementation)

The CLI needs to:
1. Detect `ApprovalRequestBlock` in the SSE stream
2. Render an approval prompt using Lipgloss (Matrix theme)
3. Accept keyboard input: `[A]pprove`, `[R]eject`, `[M]odify`
4. POST decision to `/api/approval/{requestId}`

```go
// Conceptual Bubbletea model for approval prompt
type ApprovalModel struct {
    requestID string
    toolName  string
    args      map[string]interface{}
    riskLevel string
    reason    string
    timeout   int
    selected  int // 0=approve, 1=reject, 2=modify
}

func (m ApprovalModel) View() string {
    // Render styled approval box with tool details
    // Show countdown timer
    // Highlight selected option
}
```

---

## Configuration

### application.yaml additions

```yaml
kukuvaia:
  approval:
    enabled: ${KUKUVAIA_APPROVAL_ENABLED:false}
    auto-approve-medium: true      # auto-approve MEDIUM risk in interactive mode
    timeout-seconds: 30            # auto-reject after 30 seconds
    tool-overrides:
      save_memory: LOW             # downgrade save_memory to LOW risk
      bash_run: HIGH               # force bash_run to HIGH risk
      # run_validation: MEDIUM     # custom override per tool
```

### Per-persona approval mode (future)

```yaml
# .kukuvaia/personas/careful.yaml
name: careful-assistant
approval:
  auto-approve-medium: false       # always ask for MEDIUM risk
  timeout-seconds: 60              # longer timeout for careful review
```

---

## Dependencies

| Dependency | Purpose | New? |
|-----------|---------|------|
| Spring AI BaseAdvisor | Advisor interface | Existing |
| Spring AI ToolCallback | Tool wrapper interface | Existing |
| ToolRegistryConfig | Tool registration with wrapping | Existing (modified) |
| ChatController | SSE stream with approval injection | Existing (modified) |
| OutputBlock | New ApprovalRequestBlock type | Existing (modified) |
| Reactor Sinks | Bridge approval events into SSE | Existing (Spring WebFlux) |

No new external dependencies.

---

## File Inventory

### New files (8)

| File | Description |
|------|-------------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/approval/RiskLevel.java` | Enum: LOW, MEDIUM, HIGH |
| `kukuvaia-core/src/main/java/ai/kukuvaia/approval/RiskClassifier.java` | Classifies tools by risk |
| `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalDecision.java` | User decision record |
| `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalStore.java` | Pending approvals with futures |
| `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalConfig.java` | @ConfigurationProperties |
| `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ToolApprovalAdvisor.java` | BaseAdvisor for approval flow |
| `kukuvaia-core/src/main/java/ai/kukuvaia/approval/ApprovalAwareToolCallback.java` | Tool callback wrapper |
| `kukuvaia-core/src/main/java/ai/kukuvaia/api/ApprovalController.java` | REST endpoint for decisions |

### Modified files (4)

| File | Change |
|------|--------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/output/OutputBlock.java` | Add ApprovalRequestBlock to permits |
| `kukuvaia-core/src/main/java/ai/kukuvaia/output/ApprovalRequestBlock.java` | New OutputBlock type |
| `kukuvaia-core/src/main/java/ai/kukuvaia/config/ToolRegistryConfig.java` | Wrap callbacks with approval logic |
| `kukuvaia-core/src/main/java/ai/kukuvaia/api/ChatController.java` | SSE approval stream merging |

### Test files (5)

| File | What it tests |
|------|--------------|
| `kukuvaia-core/src/test/java/ai/kukuvaia/approval/RiskClassifierTest.java` | Tool classification, overrides, edge cases |
| `kukuvaia-core/src/test/java/ai/kukuvaia/approval/ApprovalStoreTest.java` | Create, resolve, timeout, concurrent access |
| `kukuvaia-core/src/test/java/ai/kukuvaia/approval/ToolApprovalAdvisorTest.java` | Full approval flow, auto-approve, timeout |
| `kukuvaia-core/src/test/java/ai/kukuvaia/approval/ApprovalAwareToolCallbackTest.java` | Wrapper delegation, rejection handling |
| `kukuvaia-core/src/test/java/ai/kukuvaia/api/ApprovalControllerTest.java` | REST endpoint, decision resolution |

---

## Verification

### Unit tests

```bash
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.approval.*"
```

Expected:
- `RiskClassifierTest`: delete_item → HIGH, search_items → LOW, write_file → MEDIUM, config override respected
- `ApprovalStoreTest`: create + resolve → future completes; create + timeout → auto-reject; concurrent requests isolated
- `ToolApprovalAdvisorTest`: LOW → auto-approve (no blocking); HIGH → blocks until decision; disabled → all pass through
- `ApprovalAwareToolCallbackTest`: approved → delegate.call() invoked; rejected → error JSON returned; delegate never called on reject

### Integration test

```bash
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.approval.ApprovalFlowIntegrationTest"
```

End-to-end test with mocked ChatModel:
1. LLM returns tool_call for HIGH-risk tool
2. Verify ApprovalRequestBlock appears in SSE stream
3. POST approval to /api/approval/{id}
4. Verify tool executes after approval
5. Test timeout path: no POST → tool auto-rejected → LLM informed

### Manual verification

```bash
# Start with approval enabled
KUKUVAIA_APPROVAL_ENABLED=true ./gradlew :kukuvaia-app:bootRun

# In another terminal, initiate a chat that triggers a write tool
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"approval-test","message":"Save a memory about my preference for dark mode"}'

# Watch SSE stream for ApprovalRequestBlock
# Extract requestId from the block

# Approve the tool call
curl -X POST http://localhost:8080/api/approval/{requestId} \
  -H "Content-Type: application/json" \
  -d '{"decision":"approve"}'

# Verify tool executed and LLM response completed

# Test rejection
# ... repeat with {"decision":"reject","reason":"not now"}
# Verify LLM receives rejection and informs user
```

---

## Design Decisions

### Why tool callback wrapping (not advisor interception)

Spring AI's tool calling happens inside the ChatModel/ToolCallAdvisor loop, which is opaque to BaseAdvisors. A BaseAdvisor's `before()` runs before the LLM call, and `after()` runs after the entire loop. Individual tool calls within the loop are not visible at the advisor level.

By wrapping ToolCallbacks, we intercept at the exact point where each tool is about to execute, which is the correct interception point for approval.

### Why synchronous blocking (not async)

The approval flow blocks the tool execution thread until the user responds. This is intentional:
- Spring AI's tool loop expects synchronous tool results
- The LLM conversation cannot meaningfully continue without the tool result
- Async would require fundamentally changing the tool calling loop

The thread is blocked on a `CompletableFuture.get()` with timeout, which is clean and well-bounded.

### Why auto-reject on timeout (not auto-approve)

Safe default. If the user is away and a HIGH-risk tool call times out, rejecting is always safer than executing. The LLM is informed of the rejection and can suggest alternatives or wait for the user to return.

---

## Effort Estimate

| Phase | Scope | Effort |
|-------|-------|--------|
| Phase 1 | RiskLevel, RiskClassifier, ApprovalDecision | 0.5 day |
| Phase 2 | ApprovalStore (futures, timeout, cleanup) | 1 day |
| Phase 3 | ToolApprovalAdvisor + ApprovalAwareToolCallback | 1.5 days |
| Phase 4 | ApprovalRequestBlock + OutputBlock modification | 0.5 day |
| Phase 5 | ApprovalController (REST endpoint) | 0.5 day |
| Phase 6 | ChatController SSE integration (Sinks merge) | 1 day |
| Phase 7 | ToolRegistryConfig wrapping + ApprovalConfig | 0.5 day |
| Phase 8 | Tests (unit + integration) | 2 days |
| Phase 9 | CLI approval UI (Go/Bubbletea) — separate repo | 2 days |
| **Total (server only)** | | **6.5 days** |
| **Total (server + CLI)** | | **8.5 days** |

---

## Priority & Prerequisites

**Priority**: Medium-High — critical for production safety, especially when adding destructive tools or multi-user access.

**Prerequisites**:
- OutputBlock sealed hierarchy (already done)
- ChatController SSE streaming (already done)
- ToolRegistryConfig (already done)
- For CLI: kukuvaia-cli project with Bubbletea TUI (separate repo)

**Blocked by**: Nothing on the server side.

**Blocks**:
- Safe bash/shell execution tools
- Safe file modification tools
- Production deployment with destructive tool access
- Enterprise compliance requirements

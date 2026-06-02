# P03: Model Fallback Chain -- Multi-Model Routing with Automatic Failover

## Problem

kukuvaia-engine has a single active model configured via `LLM_MODEL` env var (default: `claude-sonnet-4.5`). If that model times out, returns 429 (rate limit), or hits a 5xx error, the chat fails entirely. The user sees a generic "LLM error" message and must retry manually. There is no automatic fallback to a different model or provider.

Additionally, different use cases have different quality/cost tradeoffs:
- **Interactive chat** needs the best model (sonnet-4.5 or opus-4.6) for quality.
- **Daemon tasks** should use a cheap model (nova-lite or haiku) to conserve budget.
- **Memory extraction** runs in background and only needs to extract JSON -- cheap model suffices.
- **Reasoning tasks** (complex analysis) may benefit from a reasoning-optimized model.

The existing `LlmProviderService` + `ChatModelCache` + `model_roles` table provides role-based routing (e.g., "default" -> a specific model UUID). But there is no **fallback chain** -- if the assigned model fails, the request dies.

## Current State

### Provider/model registry (V6 migration)

```sql
kukuvaia.providers    -- configured LLM endpoints (SmartGate, OpenRouter, etc.)
kukuvaia.models       -- models within a provider (claude-sonnet-4.5, nova-lite, etc.)
kukuvaia.model_roles  -- role -> model mapping ("default" -> UUID, "worker" -> UUID)
```

### LlmProviderService resolution chain

```
resolve(explicitProvider, context):
  1. Try as role name via ChatModelCache -> DB lookup
  2. Try as legacy registered provider name
  3. Fallback to "default" role
  4. Last resort: legacy "default" provider
  Failure: ProviderNotAvailableException
```

No retry, no fallback to a different model.

### ChatModelFactory

Creates `OpenAiChatModel` from `ProviderRecord` + `ModelRecord`. Uses `SecretResolver` for API key. All providers use the OpenAI-compatible API.

### AgentService error handling

```java
// AgentService.java line 65-69
} catch (Exception e) {
    String errorMsg = sanitizeError(e);
    return Flux.just(new TextBlock(errorMsg, "error"));
}
```

Catches all errors, wraps in TextBlock with "error" style. No retry, no fallback.

### Spring AI retry

Spring AI has built-in `RetryTemplate` for the same model (default: 3 attempts with exponential backoff). This retries transient errors against the **same** model. It does not try a different model.

### ModelCommand (/model)

Currently a stub. Reads `LLM_MODEL` env var. Does not actually switch models at runtime.

### SmartGate available models

Per `reference_smartgate_models.md`:
- `claude-sonnet-4.5` (primary interactive)
- `claude-haiku-4.5` (cheap, fast)
- `claude-opus-4.6` (premium reasoning)
- `gpt-4.1` (OpenAI via SmartGate)
- `amazon-nova-lite` (cheapest, fast)

### Key files

- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/LlmProviderService.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ChatModelCache.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ChatModelFactory.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/commands/ModelCommand.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java`
- `kukuvaia-app/src/main/resources/application.yaml`
- `kukuvaia-app/src/main/resources/db/migration/V6__create_providers_and_models.sql`

## Architecture

```
User request
     |
     v
AgentService.streamChat()
     |
     v
ModelRoutingService.resolveWithFallback(role, context)
     |
     +---> Try primary (role-assigned model from model_roles table)
     |         |
     |         +---> Success --> return ChatModel
     |         |
     |         +---> Failure (timeout/429/5xx)
     |                   |
     |                   v
     +---> Try secondary (fallback_chain[1] from config)
     |         |
     |         +---> Success --> return ChatModel
     |         |
     |         +---> Failure
     |                   |
     |                   v
     +---> Try tertiary (fallback_chain[2] from config)
               |
               +---> Success --> return ChatModel
               |
               +---> Failure --> throw FallbackExhaustedException

Model resolution per use case:
+------------------+-------------------+-------------------+-------------------+
| Use case         | Primary           | Secondary         | Tertiary          |
+------------------+-------------------+-------------------+-------------------+
| interactive      | claude-sonnet-4.5 | gpt-4.1           | claude-haiku-4.5  |
| daemon           | amazon-nova-lite  | claude-haiku-4.5  | claude-sonnet-4.5 |
| extraction       | amazon-nova-lite  | claude-haiku-4.5  | (none)            |
| reasoning        | claude-opus-4.6   | claude-sonnet-4.5 | gpt-4.1           |
| sub-agent worker | claude-haiku-4.5  | amazon-nova-lite  | (none)            |
+------------------+-------------------+-------------------+-------------------+

Fallback decision flow:
+---------------------+
| Call ChatModel      |
+---------------------+
         |
    Exception?
    /        \
  no          yes
   |           |
 return    classify error
           /    |     \      \
        429   5xx  timeout  other
         |     |      |       |
     fallback  fb   fallback  throw
                              (no retry)
```

### What triggers a fallback

| Error type | Detection | Fallback? | Rationale |
|-----------|-----------|-----------|-----------|
| HTTP 429 (rate limit) | Exception message contains "429" | Yes | Different model may not be rate-limited |
| HTTP 5xx (server error) | Exception message contains "500", "502", "503", "504" | Yes | Provider may be down |
| Timeout (read/connect) | `SocketTimeoutException`, "timed out" | Yes | Model may be overloaded |
| HTTP 401/403 (auth) | Exception message contains "401", "403" | No | Different model same provider has same auth |
| Malformed response | JSON parse error | No | Likely a model behavior issue, retry same model |
| Prompt too long (context overflow) | "context_length_exceeded" | No | Need shorter prompt, not different model |

## Implementation

### Step 1: Create ModelRoutingService

**`kukuvaia-core/src/main/java/ai/kukuvaia/provider/ModelRoutingService.java`:**

```java
package ai.kukuvaia.provider;

import ai.kukuvaia.agent.daemon.ExecutionContext;
import ai.kukuvaia.provider.registry.ChatModelCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a ChatModel for a given role with automatic fallback.
 * Tries models in priority order from the fallback chain configuration.
 * Records which model was actually used (for audit logging).
 */
@Service
public class ModelRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingService.class);

    private final ChatModelCache chatModelCache;
    private final FallbackChainConfig fallbackChainConfig;
    private final LlmProviderService providerService;

    public ModelRoutingService(ChatModelCache chatModelCache,
                               FallbackChainConfig fallbackChainConfig,
                               LlmProviderService providerService) {
        this.chatModelCache = chatModelCache;
        this.fallbackChainConfig = fallbackChainConfig;
        this.providerService = providerService;
    }

    /**
     * Resolve the primary model for a role. No fallback -- throws if not available.
     */
    public ChatModel resolvePrimary(String role) {
        return providerService.resolveByRole(role);
    }

    /**
     * Execute a chat call with automatic fallback on retriable failures.
     *
     * @param role    the routing role (e.g., "interactive", "daemon", "extraction")
     * @param context execution context
     * @param caller  the actual LLM call to make, given a ChatModel
     * @return the result from the first successful call
     */
    public <T> T callWithFallback(String role, ExecutionContext context,
                                   ChatModelCaller<T> caller) {
        List<String> chain = fallbackChainConfig.getChainForRole(role);

        Exception lastException = null;
        for (int i = 0; i < chain.size(); i++) {
            String modelRole = chain.get(i);
            ChatModel model;
            try {
                model = providerService.resolveByRole(modelRole);
            } catch (Exception e) {
                log.warn("Fallback chain: model for role '{}' not available, skipping", modelRole);
                continue;
            }

            try {
                T result = caller.call(model, modelRole);
                if (i > 0) {
                    log.info("Fallback succeeded: role='{}', used fallback model '{}' (attempt {})",
                            role, modelRole, i + 1);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                if (isRetriable(e)) {
                    log.warn("Fallback chain: '{}' failed with retriable error ({}), trying next. " +
                             "Attempt {}/{}",
                            modelRole, classifyError(e), i + 1, chain.size());
                } else {
                    log.error("Fallback chain: '{}' failed with non-retriable error: {}",
                            modelRole, e.getMessage());
                    throw wrapException(e);
                }
            }
        }

        throw new FallbackExhaustedException(role, chain, lastException);
    }

    private boolean isRetriable(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("429")
                || msg.contains("500") || msg.contains("502")
                || msg.contains("503") || msg.contains("504")
                || msg.contains("timed out") || msg.contains("Timeout")
                || msg.contains("Connection refused")
                || msg.contains("Read timed out");
    }

    private String classifyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "unknown";
        if (msg.contains("429")) return "rate-limited";
        if (msg.contains("500") || msg.contains("502") || msg.contains("503")) return "server-error";
        if (msg.contains("timed out") || msg.contains("Timeout")) return "timeout";
        return "other";
    }

    private RuntimeException wrapException(Exception e) {
        if (e instanceof RuntimeException re) return re;
        return new RuntimeException("LLM call failed: " + e.getMessage(), e);
    }

    /**
     * Functional interface for making an LLM call with a given ChatModel.
     */
    @FunctionalInterface
    public interface ChatModelCaller<T> {
        T call(ChatModel model, String modelRole) throws Exception;
    }

    public static class FallbackExhaustedException extends RuntimeException {
        public FallbackExhaustedException(String role, List<String> chain, Exception last) {
            super("All models in fallback chain for role '%s' failed (%s). Last error: %s"
                    .formatted(role, String.join(" -> ", chain),
                            last != null ? last.getMessage() : "unknown"));
        }
    }
}
```

### Step 2: Create FallbackChainConfig

**`kukuvaia-core/src/main/java/ai/kukuvaia/provider/FallbackChainConfig.java`:**

```java
package ai.kukuvaia.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for model fallback chains per routing role.
 * Bound from kukuvaia.models.fallback-chains in application.yaml.
 *
 * Each role maps to an ordered list of model role names to try.
 * The first entry is the primary, subsequent entries are fallbacks.
 */
@Component
@ConfigurationProperties(prefix = "kukuvaia.models")
public class FallbackChainConfig {

    private Map<String, List<String>> fallbackChains = new HashMap<>();

    /**
     * Default chain: just the "default" role with no fallback.
     */
    private static final List<String> DEFAULT_CHAIN = List.of("default");

    public Map<String, List<String>> getFallbackChains() {
        return fallbackChains;
    }

    public void setFallbackChains(Map<String, List<String>> fallbackChains) {
        this.fallbackChains = fallbackChains;
    }

    /**
     * Get the fallback chain for a role. Returns a single-element list with
     * the role itself if no chain is configured.
     */
    public List<String> getChainForRole(String role) {
        List<String> chain = fallbackChains.get(role);
        if (chain != null && !chain.isEmpty()) {
            return chain;
        }
        // Fallback: try the role directly, then "default"
        if ("default".equals(role)) {
            return DEFAULT_CHAIN;
        }
        return List.of(role, "default");
    }
}
```

### Step 3: Integrate fallback into AgentService

**`kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java`:**

Replace the direct `chatClient.prompt().call()` with `ModelRoutingService.callWithFallback()` for the chat path. The existing `ChatClient` bean is still used for the primary model; fallback creates a temporary `ChatClient` with the fallback `ChatModel`.

```java
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String USER_ID = "bartek";
    private static final String INTERACTIVE_ROLE = "interactive";

    private final ChatClient chatClient;
    private final PersonaService personaService;
    private final MemoryExtractionService extractionService;
    private final ModelRoutingService modelRoutingService;
    private final Executor extractionExecutor;

    public AgentService(ChatClient chatClient,
                        PersonaService personaService,
                        MemoryExtractionService extractionService,
                        ModelRoutingService modelRoutingService,
                        @Qualifier("memoryExtractionExecutor") Executor extractionExecutor) {
        this.chatClient = chatClient;
        this.personaService = personaService;
        this.extractionService = extractionService;
        this.modelRoutingService = modelRoutingService;
        this.extractionExecutor = extractionExecutor;
    }

    public Flux<OutputBlock> streamChat(String sessionId, String message) {
        log.info("Chat request: sessionId={}, messageLength={}", sessionId, message.length());

        try {
            PersonaSpec persona = personaService.getActivePersona(sessionId);

            // Use primary ChatClient first; on retriable failure, fallback models
            String response = modelRoutingService.callWithFallback(
                    INTERACTIVE_ROLE,
                    ExecutionContext.INTERACTIVE,
                    (model, modelRole) -> {
                        // For primary model, use the pre-configured ChatClient (has all advisors)
                        // For fallback models, build a minimal ChatClient
                        if ("interactive".equals(modelRole)) {
                            return chatClient.prompt()
                                    .system(persona.systemPrompt())
                                    .user(message)
                                    .advisors(spec -> spec
                                            .param("chat_memory_conversation_id", sessionId)
                                            .param("kukuvaia.userId", USER_ID))
                                    .call()
                                    .content();
                        } else {
                            return ChatClient.builder(model)
                                    .build()
                                    .prompt()
                                    .system(persona.systemPrompt())
                                    .user(message)
                                    .call()
                                    .content();
                        }
                    });

            triggerExtraction(USER_ID, sessionId);
            return Flux.just(new TextBlock(response, null));

        } catch (ModelRoutingService.FallbackExhaustedException e) {
            log.error("All fallback models exhausted: {}", e.getMessage());
            return Flux.just(new TextBlock(
                    "All LLM models are currently unavailable. Try again later.", "error"));
        } catch (Exception e) {
            log.error("Chat failed: sessionId={}, error={}", sessionId, e.getMessage());
            return Flux.just(new TextBlock(sanitizeError(e), "error"));
        }
    }
}
```

### Step 4: Wire up model roles in application.yaml

**`kukuvaia-app/src/main/resources/application.yaml` additions:**

```yaml
kukuvaia:
  models:
    fallback-chains:
      interactive:
        - interactive    # claude-sonnet-4.5 (DB role assignment)
        - economy        # claude-haiku-4.5
        - default        # whatever is configured as default
      daemon:
        - worker         # amazon-nova-lite
        - economy        # claude-haiku-4.5
      extraction:
        - worker         # amazon-nova-lite
        - economy        # claude-haiku-4.5
      reasoning:
        - advisor        # claude-opus-4.6
        - interactive    # claude-sonnet-4.5
        - default
```

These role names (`interactive`, `economy`, `worker`, `advisor`) are mapped to model UUIDs in the `kukuvaia.model_roles` table. The role assignments are managed via `/model assign <role> <model-name>` command or directly in the database.

### Step 5: Upgrade /model command

**`kukuvaia-core/src/main/java/ai/kukuvaia/commands/ModelCommand.java`:**

Complete rewrite to support:
- `/model` -- show current model and role assignments
- `/model list` -- list all available models with provider, tier, status
- `/model chain` -- show fallback chains per role
- `/model assign <role> <model-id>` -- assign a model to a role (updates model_roles table)

```java
package ai.kukuvaia.commands;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TableBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.provider.FallbackChainConfig;
import ai.kukuvaia.provider.registry.ChatModelCache;
import ai.kukuvaia.provider.registry.ModelRepository;
import ai.kukuvaia.provider.registry.ModelRecord;
import ai.kukuvaia.provider.registry.ModelRoleRepository;
import ai.kukuvaia.provider.registry.ProviderRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ModelCommand implements SlashCommand {

    private final ModelRepository modelRepository;
    private final ModelRoleRepository modelRoleRepository;
    private final ProviderRepository providerRepository;
    private final FallbackChainConfig fallbackChainConfig;
    private final ChatModelCache chatModelCache;

    public ModelCommand(ModelRepository modelRepository,
                        ModelRoleRepository modelRoleRepository,
                        ProviderRepository providerRepository,
                        FallbackChainConfig fallbackChainConfig,
                        ChatModelCache chatModelCache) {
        this.modelRepository = modelRepository;
        this.modelRoleRepository = modelRoleRepository;
        this.providerRepository = providerRepository;
        this.fallbackChainConfig = fallbackChainConfig;
        this.chatModelCache = chatModelCache;
    }

    @Override
    public String name() { return "model"; }

    @Override
    public String description() {
        return "Model management (usage: /model [list|chain|assign <role> <model>])";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        if (args == null || args.isBlank()) {
            return showCurrentRoles();
        }

        String[] parts = args.trim().split("\\s+", 3);
        return switch (parts[0]) {
            case "list" -> listModels();
            case "chain" -> showFallbackChains();
            case "assign" -> {
                if (parts.length < 3) {
                    yield List.of(new TextBlock("Usage: /model assign <role> <model-id>", "error"));
                }
                yield assignRole(parts[1], parts[2]);
            }
            default -> List.of(new TextBlock("Unknown subcommand: " + parts[0] +
                    ". Use: list, chain, assign", "error"));
        };
    }

    private List<OutputBlock> showCurrentRoles() {
        var roles = modelRoleRepository.findAll();
        if (roles.isEmpty()) {
            return List.of(new TextBlock("No model roles configured. Use /model assign <role> <model-id>", null));
        }
        var headers = List.of("Role", "Model", "Provider");
        var rows = new ArrayList<List<String>>();
        for (var role : roles) {
            var model = modelRepository.findById(role.modelId());
            var modelName = model.map(ModelRecord::modelId).orElse("unknown");
            var providerName = model.flatMap(m -> providerRepository.findById(m.providerId()))
                    .map(p -> p.name()).orElse("unknown");
            rows.add(List.of(role.role(), modelName, providerName));
        }
        return List.of(new TableBlock("Model Role Assignments", headers, rows));
    }

    private List<OutputBlock> listModels() {
        var models = modelRepository.findAll();
        var headers = List.of("ID", "Model", "Provider", "Tier", "Enabled");
        var rows = new ArrayList<List<String>>();
        for (var model : models) {
            var providerName = providerRepository.findById(model.providerId())
                    .map(p -> p.name()).orElse("unknown");
            rows.add(List.of(
                    model.id().toString().substring(0, 8),
                    model.modelId(),
                    providerName,
                    model.tier(),
                    String.valueOf(model.enabled())
            ));
        }
        return List.of(new TableBlock("Available Models", headers, rows));
    }

    private List<OutputBlock> showFallbackChains() {
        var chains = fallbackChainConfig.getFallbackChains();
        if (chains.isEmpty()) {
            return List.of(new TextBlock("No fallback chains configured.", null));
        }
        var headers = List.of("Role", "Chain (primary -> fallback)");
        var rows = new ArrayList<List<String>>();
        for (var entry : chains.entrySet()) {
            rows.add(List.of(entry.getKey(), String.join(" -> ", entry.getValue())));
        }
        return List.of(new TableBlock("Fallback Chains", headers, rows));
    }

    private List<OutputBlock> assignRole(String role, String modelIdPrefix) {
        // Find model by prefix match on UUID or modelId
        var allModels = modelRepository.findAll();
        var match = allModels.stream()
                .filter(m -> m.id().toString().startsWith(modelIdPrefix)
                        || m.modelId().equalsIgnoreCase(modelIdPrefix))
                .findFirst();

        if (match.isEmpty()) {
            return List.of(new TextBlock("Model not found: " + modelIdPrefix +
                    ". Use /model list to see available models.", "error"));
        }

        modelRoleRepository.upsert(role, match.get().id());
        chatModelCache.refreshRoles();

        return List.of(new TextBlock(
                "Assigned model '%s' to role '%s'".formatted(match.get().modelId(), role), null));
    }
}
```

### Step 6: Wire fallback into SubAgentFactory and DaemonAgentService

**SubAgentFactory:** Replace the direct `providerService.resolve()` call with `modelRoutingService.callWithFallback()` using the sub-agent's tier/role as the routing key.

**DaemonAgentService:** Wrap the `subAgentFactory.execute()` call in a try-catch that leverages fallback (the sub-agent factory already handles this if wired with ModelRoutingService).

These are straightforward wiring changes -- the `ModelRoutingService.callWithFallback()` pattern is the same.

## Configuration

### Full configuration block

```yaml
kukuvaia:
  models:
    fallback-chains:
      interactive:
        - interactive
        - economy
        - default
      daemon:
        - worker
        - economy
      extraction:
        - worker
        - economy
      reasoning:
        - advisor
        - interactive
        - default
```

### Database seed (model_roles table)

```sql
-- Role assignments (run after providers and models are registered via /login)
INSERT INTO kukuvaia.model_roles (role, model_id, description)
VALUES
  ('interactive', (SELECT id FROM kukuvaia.models WHERE model_id = 'claude-sonnet-4.5' LIMIT 1),
   'Primary model for interactive chat'),
  ('economy', (SELECT id FROM kukuvaia.models WHERE model_id = 'claude-haiku-4.5' LIMIT 1),
   'Cheap fast model for fallbacks'),
  ('worker', (SELECT id FROM kukuvaia.models WHERE model_id = 'amazon-nova-lite' LIMIT 1),
   'Cheapest model for background tasks'),
  ('advisor', (SELECT id FROM kukuvaia.models WHERE model_id = 'claude-opus-4.6' LIMIT 1),
   'Premium reasoning model')
ON CONFLICT (role) DO UPDATE SET model_id = EXCLUDED.model_id, updated_at = NOW();
```

## Dependencies

No new external dependencies. All routing logic is built on top of existing:

| Existing component | Used for |
|-------------------|----------|
| `ChatModelCache` | Model instance cache by UUID |
| `ChatModelFactory` | Creating new `OpenAiChatModel` instances |
| `LlmProviderService` | Legacy resolution + `resolveByRole()` |
| `ModelRoleRepository` | Role -> model UUID mapping |
| `ProviderRepository` / `ModelRepository` | Model/provider metadata |

New internal classes:

| Class | Module |
|-------|--------|
| `ModelRoutingService` | kukuvaia-core (provider package) |
| `FallbackChainConfig` | kukuvaia-core (provider package) |

## Verification

### 1. Build passes

```bash
./gradlew clean build
```

### 2. Fallback chain config loads

```bash
./gradlew :kukuvaia-app:bootRun &
# Check logs for:
# "Refreshed role index: N roles loaded"
```

### 3. Unit test: ModelRoutingService fallback

```java
@Test
@DisplayName("callWithFallback tries secondary model on 429 from primary")
void callWithFallback_primaryRateLimited_usesSecondary() {
    // Given: primary throws 429, secondary succeeds
    when(providerService.resolveByRole("interactive")).thenReturn(primaryModel);
    when(providerService.resolveByRole("economy")).thenReturn(secondaryModel);
    when(fallbackConfig.getChainForRole("interactive"))
            .thenReturn(List.of("interactive", "economy"));

    AtomicInteger callCount = new AtomicInteger(0);
    String result = routingService.callWithFallback("interactive", ExecutionContext.INTERACTIVE,
            (model, role) -> {
                if (callCount.getAndIncrement() == 0) {
                    throw new RuntimeException("429 Too Many Requests");
                }
                return "fallback response";
            });

    assertThat(result).isEqualTo("fallback response");
    assertThat(callCount.get()).isEqualTo(2);
}

@Test
@DisplayName("callWithFallback does not retry on 401")
void callWithFallback_authError_throwsImmediately() {
    when(fallbackConfig.getChainForRole("interactive"))
            .thenReturn(List.of("interactive", "economy"));
    when(providerService.resolveByRole("interactive")).thenReturn(primaryModel);

    assertThatThrownBy(() ->
            routingService.callWithFallback("interactive", ExecutionContext.INTERACTIVE,
                    (model, role) -> { throw new RuntimeException("401 Unauthorized"); })
    ).isInstanceOf(RuntimeException.class)
     .hasMessageContaining("401");
}

@Test
@DisplayName("callWithFallback throws FallbackExhaustedException when all fail")
void callWithFallback_allFail_throwsFallbackExhausted() {
    when(fallbackConfig.getChainForRole("interactive"))
            .thenReturn(List.of("interactive", "economy"));
    when(providerService.resolveByRole("interactive")).thenReturn(primaryModel);
    when(providerService.resolveByRole("economy")).thenReturn(secondaryModel);

    assertThatThrownBy(() ->
            routingService.callWithFallback("interactive", ExecutionContext.INTERACTIVE,
                    (model, role) -> { throw new RuntimeException("503 Service Unavailable"); })
    ).isInstanceOf(ModelRoutingService.FallbackExhaustedException.class);
}
```

### 4. /model command works

```bash
curl -X POST http://localhost:8080/api/commands/model \
  -H "Content-Type: application/json" \
  -d '{"args":"list","sessionId":"test"}'

curl -X POST http://localhost:8080/api/commands/model \
  -H "Content-Type: application/json" \
  -d '{"args":"chain","sessionId":"test"}'
```

### 5. Integration test: actual fallback on timeout

This requires a mock HTTP server that simulates timeout for one model and success for another. Use WireMock in an `@SpringBootTest`:

```java
@SpringBootTest
@AutoConfigureWireMock(port = 0)
class ModelFallbackIntegrationTest {
    // Configure primary model -> WireMock with 30s delay (timeout)
    // Configure secondary model -> WireMock with instant response
    // Verify chat succeeds via secondary
}
```

## Effort Estimate

| Task | Effort | Notes |
|------|--------|-------|
| Create FallbackChainConfig (Step 2) | 0.5h | Config properties + test |
| Create ModelRoutingService (Step 1) | 2h | Core logic + unit tests |
| Integrate into AgentService (Step 3) | 1.5h | Refactor chat path + tests |
| Application.yaml config (Step 4) | 0.5h | YAML + documentation |
| Upgrade /model command (Step 5) | 2h | Full rewrite + tests |
| Wire into SubAgentFactory + Daemon (Step 6) | 1h | Pattern application |
| Integration test with WireMock | 1.5h | End-to-end fallback test |
| **Total** | **~9h** | |

## Priority & Prerequisites

**Priority:** High -- this directly impacts user experience. A single model timeout should not kill the entire chat.

**Prerequisites:**
- The `kukuvaia.providers`, `kukuvaia.models`, and `kukuvaia.model_roles` tables must be populated (V6 migration is already applied).
- At least two models must be registered and assigned to roles. Without them, the fallback chain degenerates to a single model (same as today).
- The `/login` or `/model assign` command must be functional to populate role assignments.

**Sequencing:**
- P01 (observability) is recommended before this plan so fallback events are observable via metrics from day one. However, it is not strictly required.
- This plan is independent of P02 (structured output), P04 (summarization), and P05 (eval pipeline).

**Risks:**
- **Advisor chain on fallback:** When the primary `ChatClient` (with all advisors) fails, the fallback creates a minimal `ChatClient` without advisors (no memory, no tool calling). This is intentional for emergency fallback but means fallback responses lack context. A future improvement is to build a full advisor chain for fallback models too.
- **Cost explosion:** If the primary model is consistently failing, all traffic shifts to the secondary (potentially more expensive) model. Mitigation: configure cheaper models as fallbacks, add circuit breaker logic in a future iteration.
- **Secret resolution:** All models in the chain must have valid API keys. A model with an expired key in the middle of the chain will fail with a non-retriable auth error and stop the chain.

package ai.kukuvaia.agent.subagent;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.config.ToolRegistryConfig;
import ai.kukuvaia.provider.service.LlmProviderService;
import ai.kukuvaia.security.ToolResultSanitizingAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates isolated ChatClient instances for specialist sub-agents.
 * Integrates SubAgentGuard for all security controls.
 *
 * Covers: Finding #6 (recursive spawning), #7 (tool escalation),
 * #12 (prompt injection), sub-agent DoS controls.
 */
@Component
public class SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SubAgentFactory.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 300; // 5 minutes

    private final LlmProviderService providerService;
    private final SubAgentGuard guard;
    private final ToolResultSanitizingAdvisor toolResultAdvisor;
    private final ToolRegistryConfig toolRegistry;
    private final Map<String, SubAgentSpec> specs; // loaded from YAML at startup
    private final ExecutorService workerExecutor;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public SubAgentFactory(LlmProviderService providerService,
                            SubAgentGuard guard,
                            ToolResultSanitizingAdvisor toolResultAdvisor,
                            @Lazy ToolRegistryConfig toolRegistry,
                            SubAgentSpecLoader specLoader,
                            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.providerService = providerService;
        this.guard = guard;
        this.toolResultAdvisor = toolResultAdvisor;
        this.toolRegistry = toolRegistry;
        this.specs = specLoader.loadAll();
        this.meterRegistry = meterRegistry;
        this.workerExecutor = Executors.newFixedThreadPool(
                guard.maxParallelWorkers(),
                Thread.ofVirtual().name("kukuvaia-worker-", 0).factory());
    }

    /**
     * Execute a task via specialist sub-agent with full security enforcement.
     *
     * @param task             task description (already sanitized by caller)
     * @param specialistType   specialist name (e.g., "analyst", "validator")
     * @param context          execution context (INTERACTIVE or DAEMON)
     * @param providerOverride explicit provider or null for context default
     * @return sub-agent's synthesized result
     */
    public String execute(String task, String specialistType,
                           ExecutionContext context, String providerOverride) {
        return execute(task, specialistType, context, providerOverride, 0, null);
    }

    /**
     * Internal execute with depth tracking and persona tool filtering.
     */
    public String execute(String task, String specialistType,
                           ExecutionContext context, String providerOverride,
                           int currentDepth, Collection<String> personaTools) {
        // Security: depth guard — prevent recursive spawning
        guard.validateDepth(currentDepth);

        SubAgentSpec spec = specs.get(specialistType);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown specialist type: " + specialistType);
        }

        // Security: filter tools (remove delegation tools + intersect with persona)
        Set<String> safeTools = guard.filterTools(spec.tools(), personaTools);

        // Security: harden system prompt with anti-injection instructions
        String safePrompt = guard.hardenSystemPrompt(spec.systemPrompt());

        // Resolve model: tier → provider → context default
        // Tier-based resolution uses DB-backed roles (e.g., "worker" → Haiku)
        String effectiveProvider = providerOverride != null ? providerOverride : spec.provider();
        ChatModel chatModel;
        if (spec.tier() != null && !spec.tier().isBlank()) {
            try {
                chatModel = providerService.resolveByRole(spec.tier());
                effectiveProvider = spec.tier(); // for logging
                log.debug("Resolved tier '{}' to model for specialist '{}'", spec.tier(), specialistType);
            } catch (Exception e) {
                log.warn("Tier '{}' not available, falling back to provider resolution", spec.tier());
                chatModel = providerService.resolve(effectiveProvider, context);
            }
        } else {
            chatModel = providerService.resolve(effectiveProvider, context);
        }

        String resolvedProvider = effectiveProvider; // effectively final for lambda

        log.info("Creating sub-agent: specialist={}, provider={}, context={}, tools={}, depth={}",
                specialistType, resolvedProvider, context, safeTools.size(), currentDepth);

        // Build isolated ChatClient
        // Note: tool calling in Spring AI M6 is handled by the ChatModel, not an advisor
        ChatClient subAgent = ChatClient.builder(chatModel)
                .defaultSystem(safePrompt)
                .defaultAdvisors(
                        toolResultAdvisor         // Anti-prompt-injection in tool results
                )
                .defaultToolCallbacks(toolRegistry.resolveAll(safeTools))
                .build();

        // Execute with timeout — prevents runaway sub-agents
        long timeoutSeconds = spec.timeoutSeconds() > 0
                ? spec.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

        var chatOptions = buildSubAgentOptions(spec);

        io.micrometer.core.instrument.Timer.Sample sample =
                io.micrometer.core.instrument.Timer.start(meterRegistry);
        String status = "success";
        try {
            return CompletableFuture.supplyAsync(() ->
                    subAgent.prompt()
                            .options(chatOptions)
                            .user(task)
                            .advisors(advisorSpec -> advisorSpec.params(Map.of(
                                    "kukuvaia.provider", resolvedProvider,
                                    "kukuvaia.executionContext", context.name(),
                                    "kukuvaia.specialist", specialistType
                            )))
                            .call().content()
            ).get(timeoutSeconds, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            status = "timeout";
            log.error("Sub-agent '{}' timed out after {}s", specialistType, timeoutSeconds);
            throw new SubAgentTimeoutException(specialistType, timeoutSeconds);
        } catch (Exception e) {
            status = "error";
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Sub-agent execution failed: " + e.getMessage(), e);
        } finally {
            sample.stop(io.micrometer.core.instrument.Timer.builder("kukuvaia.subagent.duration")
                    .tag("specialist", specialistType)
                    .tag("provider", resolvedProvider)
                    .tag("context", context.name())
                    .tag("status", status)
                    .register(meterRegistry));
        }
    }

    /**
     * Execute multiple worker tasks in parallel with bounded concurrency.
     * Distributes tasks round-robin across all configured worker models
     * (worker, worker-2, worker-3, ...). If only one worker model is configured,
     * all tasks use it.
     *
     * @param tasks            list of worker tasks to execute concurrently
     * @param context          execution context (INTERACTIVE or DAEMON)
     * @param providerOverride explicit provider or null for context default
     * @param personaTools     active persona's allowed tools (null = all)
     * @return results in same order as input tasks
     */
    public List<WorkerResult> executeParallel(List<WorkerTask> tasks,
                                               ExecutionContext context,
                                               String providerOverride,
                                               Collection<String> personaTools) {
        guard.validateParallelCount(tasks.size());

        // Resolve available worker models for round-robin distribution
        List<ChatModel> workerModels = providerService.resolveWorkerModels();
        if (workerModels.isEmpty()) {
            log.warn("No worker models configured — falling back to default resolution per specialist");
        } else {
            log.info("Starting parallel execution of {} workers across {} worker models",
                    tasks.size(), workerModels.size());
        }

        List<CompletableFuture<WorkerResult>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            WorkerTask task = tasks.get(i);
            // Round-robin: task 0 → model 0, task 1 → model 1, task 2 → model 0, ...
            ChatModel workerModel = workerModels.isEmpty()
                    ? null : workerModels.get(i % workerModels.size());
            futures.add(CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                try {
                    String result = executeWithModel(task.task(), task.specialistType(),
                            context, providerOverride, personaTools, workerModel);
                    long duration = System.currentTimeMillis() - start;
                    log.info("Worker '{}' completed in {}ms", task.specialistType(), duration);
                    return new WorkerResult(task.specialistType(),
                            WorkerResult.WorkerStatus.COMPLETED,
                            result, null, duration);
                } catch (SubAgentTimeoutException e) {
                    long duration = System.currentTimeMillis() - start;
                    log.warn("Worker '{}' timed out after {}ms", task.specialistType(), duration);
                    return new WorkerResult(task.specialistType(),
                            WorkerResult.WorkerStatus.TIMEOUT,
                            null, e.getMessage(), duration);
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    log.error("Worker '{}' failed after {}ms: {}",
                            task.specialistType(), duration, e.getMessage());
                    return new WorkerResult(task.specialistType(),
                            WorkerResult.WorkerStatus.FAILED,
                            null, e.getMessage(), duration);
                }
            }, workerExecutor));
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * Execute a specialist with an explicit ChatModel override (used by parallel workers).
     * Falls back to normal tier/provider resolution if modelOverride is null.
     */
    private String executeWithModel(String task, String specialistType,
                                     ExecutionContext context, String providerOverride,
                                     Collection<String> personaTools, ChatModel modelOverride) {
        guard.validateDepth(0);

        SubAgentSpec spec = specs.get(specialistType);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown specialist type: " + specialistType);
        }

        Set<String> safeTools = guard.filterTools(spec.tools(), personaTools);
        String safePrompt = guard.hardenSystemPrompt(spec.systemPrompt());

        // Use explicit model or fall back to normal resolution
        ChatModel chatModel;
        String resolvedProvider;
        if (modelOverride != null) {
            chatModel = modelOverride;
            resolvedProvider = "worker-pool";
        } else {
            String effectiveProvider = providerOverride != null ? providerOverride : spec.provider();
            if (spec.tier() != null && !spec.tier().isBlank()) {
                try {
                    chatModel = providerService.resolveByRole(spec.tier());
                    effectiveProvider = spec.tier();
                } catch (Exception e) {
                    chatModel = providerService.resolve(effectiveProvider, context);
                }
            } else {
                chatModel = providerService.resolve(effectiveProvider, context);
            }
            resolvedProvider = effectiveProvider;
        }

        log.info("Creating worker sub-agent: specialist={}, provider={}, tools={}",
                specialistType, resolvedProvider, safeTools.size());

        ChatClient subAgent = ChatClient.builder(chatModel)
                .defaultSystem(safePrompt)
                .defaultAdvisors(toolResultAdvisor)
                .defaultToolCallbacks(toolRegistry.resolveAll(safeTools))
                .build();

        long timeoutSeconds = spec.timeoutSeconds() > 0
                ? spec.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

        var chatOptions = buildSubAgentOptions(spec);

        try {
            return CompletableFuture.supplyAsync(() ->
                    subAgent.prompt()
                            .options(chatOptions)
                            .user(task)
                            .advisors(advisorSpec -> advisorSpec.params(Map.of(
                                    "kukuvaia.provider", resolvedProvider,
                                    "kukuvaia.executionContext", context.name(),
                                    "kukuvaia.specialist", specialistType
                            )))
                            .call().content()
            ).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new SubAgentTimeoutException(specialistType, timeoutSeconds);
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Sub-agent execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * List available specialist types.
     */
    public Set<String> availableSpecialists() {
        return specs.keySet();
    }

    /**
     * Build chat options for sub-agent. Skips model override when tier-based resolution
     * was used (model already resolved from DB via role).
     */
    private OpenAiChatOptions buildSubAgentOptions(SubAgentSpec spec) {
        var builder = OpenAiChatOptions.builder()
                .maxTokens(spec.maxTokens())
                .temperature(spec.temperature());
        // Only set model from YAML if no tier-based resolution (avoids overriding DB model)
        boolean tierResolved = spec.tier() != null && !spec.tier().isBlank();
        if (!tierResolved && spec.model() != null && !spec.model().isBlank()) {
            builder.model(spec.model());
        }
        return builder.build();
    }

    public static class SubAgentTimeoutException extends RuntimeException {
        public SubAgentTimeoutException(String specialist, long timeoutSeconds) {
            super("Sub-agent '%s' timed out after %d seconds".formatted(specialist, timeoutSeconds));
        }
    }
}

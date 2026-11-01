package ai.kukuvaia.agent.subagent;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.config.ToolRegistryConfig;
import ai.kukuvaia.provider.service.LlmProviderService;
import ai.kukuvaia.provider.service.ProviderAuditLog;
import ai.kukuvaia.security.ToolResultSanitizingAdvisor;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates isolated ChatClient instances for specialist sub-agents.
 * Integrates SubAgentGuard for all security controls.
 *
 * <p>Every call goes through {@link #runSpecialist}: tool filtering, prompt hardening, model
 * resolution, a per-call {@code ChatClient} with {@link ProviderAuditLog} (metrics + token log)
 * and {@link ToolResultSanitizingAdvisor}, and a hard timeout. Spring AI 2.x registers its
 * {@code ToolCallingAdvisor} automatically, so tools still run inside the client.
 *
 * Covers: Finding #6 (recursive spawning), #7 (tool escalation),
 * #9 (structured request logging), #12 (prompt injection), sub-agent DoS controls.
 */
@Component
public class SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SubAgentFactory.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 300; // 5 minutes

    private final LlmProviderService providerService;
    private final SubAgentGuard guard;
    private final ToolResultSanitizingAdvisor toolResultAdvisor;
    private final ProviderAuditLog providerAuditLog;
    private final ToolRegistryConfig toolRegistry;
    private final Map<String, SubAgentSpec> specs; // loaded from YAML at startup
    /** Runs the outer per-worker tasks of {@link #executeParallel}; bounded by the guard's limit. */
    private final ExecutorService workerExecutor;
    /**
     * Runs the actual LLM call of every sub-agent invocation. A dedicated executor (not the
     * common fork-join pool) so a timed-out call can be cancelled with an interrupt. Concurrency
     * is bounded upstream: by {@link SubAgentGuard#validateParallelCount} for parallel workers and
     * by the daemon/interactive callers for single calls.
     */
    private final ExecutorService callExecutor;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public SubAgentFactory(LlmProviderService providerService,
                            SubAgentGuard guard,
                            ToolResultSanitizingAdvisor toolResultAdvisor,
                            ProviderAuditLog providerAuditLog,
                            @Lazy ToolRegistryConfig toolRegistry,
                            SubAgentSpecLoader specLoader,
                            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.providerService = providerService;
        this.guard = guard;
        this.toolResultAdvisor = toolResultAdvisor;
        this.providerAuditLog = providerAuditLog;
        this.toolRegistry = toolRegistry;
        this.specs = specLoader.loadAll();
        this.meterRegistry = meterRegistry;
        this.workerExecutor = Executors.newFixedThreadPool(
                guard.maxParallelWorkers(),
                Thread.ofVirtual().name("kukuvaia-worker-", 0).factory());
        this.callExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("kukuvaia-subagent-", 0).factory());
    }

    @PreDestroy
    void shutdown() {
        workerExecutor.shutdownNow();
        callExecutor.shutdownNow();
    }

    /**
     * Execute a task via specialist sub-agent with full security enforcement.
     *
     * @param task             task description (already sanitized by caller)
     * @param specialistType   specialist name (e.g., "analyst", "summarizer")
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
        return executeForResult(task, specialistType, context, providerOverride, currentDepth, personaTools)
                .content();
    }

    /**
     * Same as {@link #execute(String, String, ExecutionContext, String, int, Collection)} but also
     * returns the token usage reported by the model — used by callers that enforce budgets.
     */
    public SubAgentResult executeForResult(String task, String specialistType,
                                           ExecutionContext context, String providerOverride,
                                           int currentDepth, Collection<String> personaTools) {
        return executeForResult(task, specialistType, context, providerOverride, currentDepth, personaTools, 0);
    }

    /** Variant without a max_tokens cap. */
    public SubAgentResult executeForResult(String task, String specialistType,
                                           ExecutionContext context, String providerOverride,
                                           int currentDepth, Collection<String> personaTools,
                                           int maxToolRoundsCap) {
        return executeForResult(task, specialistType, context, providerOverride, currentDepth, personaTools,
                maxToolRoundsCap, 0);
    }

    /**
     * Variant used by the daemon runtime: {@code maxToolRoundsCap > 0} / {@code maxTokensCap > 0} lower
     * (never raise) the specialist's {@code max_tool_rounds} / {@code max_tokens} for this call.
     */
    public SubAgentResult executeForResult(String task, String specialistType,
                                           ExecutionContext context, String providerOverride,
                                           int currentDepth, Collection<String> personaTools,
                                           int maxToolRoundsCap, int maxTokensCap) {
        return executeForResult(task, specialistType, context, providerOverride, currentDepth, personaTools,
                maxToolRoundsCap, maxTokensCap, null);
    }

    public SubAgentResult executeForResult(String task, String specialistType,
                                           ExecutionContext context, String providerOverride,
                                           int currentDepth, Collection<String> personaTools,
                                           int maxToolRoundsCap, int maxTokensCap, ExecutionLifetime lifetime) {
        // Security: depth guard — prevent recursive spawning
        guard.validateDepth(currentDepth);

        io.micrometer.core.instrument.Timer.Sample sample =
                io.micrometer.core.instrument.Timer.start(meterRegistry);
        String status = "success";
        String[] providerHolder = {"unresolved"};
        try {
            return runSpecialist(task, specialistType, context, providerOverride, personaTools,
                    null, currentDepth, providerHolder, maxToolRoundsCap, maxTokensCap, lifetime);
        } catch (SubAgentTimeoutException e) {
            status = "timeout";
            throw e;
        } catch (RuntimeException e) {
            status = "error";
            throw e;
        } finally {
            sample.stop(io.micrometer.core.instrument.Timer.builder("kukuvaia.subagent.duration")
                    .tag("specialist", specialistType)
                    .tag("provider", providerHolder[0])
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
        // Context-aware: under DAEMON, Copilot-backed worker roles are excluded (opt-in only).
        List<ChatModel> workerModels = providerOverride == null
                ? providerService.resolveWorkerModels(context) : List.of();
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
                    String result = runSpecialist(task.task(), task.specialistType(),
                            context, providerOverride, personaTools, workerModel, 0,
                            new String[]{"worker-pool"}, 0, 0, null).content();
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
     * The single code path every sub-agent call goes through.
     *
     * @param modelOverride  explicit model (parallel worker pool) or {@code null} for tier/provider resolution
     * @param providerHolder out-parameter: receives the provider label for metrics
     */
    private SubAgentResult runSpecialist(String task, String specialistType,
                                         ExecutionContext context, String providerOverride,
                                         Collection<String> personaTools, ChatModel modelOverride,
                                         int currentDepth, String[] providerHolder, int maxToolRoundsCap,
                                         int maxTokensCap, ExecutionLifetime lifetime) {
        guard.validateDepth(currentDepth);

        SubAgentSpec spec = specs.get(specialistType);
        if (spec == null) {
            throw new UnknownSpecialistException(specialistType);
        }

        // Security: filter tools (remove delegation tools + intersect with persona)
        Set<String> safeTools = guard.filterTools(spec.tools(), personaTools);

        // Security: harden system prompt with anti-injection instructions
        String safePrompt = guard.hardenSystemPrompt(spec.systemPrompt());

        // Resolve model: explicit override → tier → provider → context default
        ChatModel chatModel;
        String resolvedProvider;
        if (modelOverride != null) {
            chatModel = modelOverride;
            resolvedProvider = providerHolder[0];
        } else {
            String effectiveProvider = providerOverride != null ? providerOverride
                    : context == ExecutionContext.DAEMON ? null : spec.provider();
            if (providerOverride != null) {
                // The job's explicit provider is the trusted opt-in; a specialist tier must not replace it.
                chatModel = providerService.resolve(providerOverride, context);
            } else if (spec.tier() != null && !spec.tier().isBlank()) {
                try {
                    // Context-aware: under DAEMON a Copilot-backed tier is refused (not an explicit opt-in).
                    chatModel = providerService.resolveByRole(spec.tier(), context);
                    effectiveProvider = spec.tier(); // for logging
                    log.debug("Resolved tier '{}' to model for specialist '{}'", spec.tier(), specialistType);
                } catch (LlmProviderService.ProviderNotAvailableException e) {
                    log.warn("Tier '{}' not available, falling back to provider resolution", spec.tier());
                    chatModel = providerService.resolve(effectiveProvider, context);
                }
            } else {
                chatModel = providerService.resolve(effectiveProvider, context);
            }
            resolvedProvider = effectiveProvider != null ? effectiveProvider : "default";
        }
        providerHolder[0] = resolvedProvider;

        log.info("Creating sub-agent: specialist={}, provider={}, context={}, tools={}, depth={}",
                specialistType, resolvedProvider, context, safeTools.size(), currentDepth);

        // Security (DoS): every tool of this call shares the specialist's max_tool_rounds budget.
        int maxToolRounds = maxToolRoundsCap > 0 ? Math.min(spec.maxToolRounds(), maxToolRoundsCap) : spec.maxToolRounds();
        var toolLimiter = new ToolRoundLimiter(specialistType, maxToolRounds);

        // Isolated ChatClient. ToolCallingAdvisor is auto-registered by Spring AI 2.x.
        var meteredModel = new UsageTrackingModel(chatModel);
        ChatClient subAgent = ChatClient.builder(meteredModel)
                .defaultSystem(safePrompt)
                .defaultAdvisors(
                        providerAuditLog,         // Provider/context/specialist + token metrics (finding #9)
                        toolResultAdvisor         // Anti-prompt-injection in tool results
                )
                .defaultTools((Object[]) toolLimiter.wrap(toolRegistry.resolveAll(safeTools)))
                .build();

        long timeoutSeconds = spec.timeoutSeconds() > 0
                ? spec.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
        var chatOptions = buildSubAgentOptions(spec, maxTokensCap);

        java.util.concurrent.Callable<SubAgentResult> work = () -> {
            SubAgentResult result = SubAgentResult.from(subAgent.prompt()
                        .options(chatOptions)
                        .user(task)
                        .advisors(advisorSpec -> advisorSpec.params(Map.of(
                                "kukuvaia.provider", resolvedProvider,
                                "kukuvaia.executionContext", context.name(),
                                "kukuvaia.specialist", specialistType
                        )))
                        .call().chatResponse());
            return new SubAgentResult(result.content(), result.promptTokens(), result.completionTokens(),
                    result.usageReported() && meteredModel.complete, result.model());
        };
        Future<SubAgentResult> call = lifetime == null ? callExecutor.submit(work) : lifetime.submit(callExecutor, work);
        try {
            return call.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Stop the call reaching tools, then interrupt it instead of letting it run on.
            toolLimiter.close();
            call.cancel(true);
            log.error("Sub-agent '{}' timed out after {}s — call cancelled", specialistType, timeoutSeconds);
            throw new SubAgentTimeoutException(specialistType, timeoutSeconds);
        } catch (InterruptedException e) {
            toolLimiter.close();
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sub-agent execution interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Sub-agent execution failed: " + e.getCause(), e.getCause());
        }
    }

    /**
     * List available specialist types.
     */
    public Set<String> availableSpecialists() {
        return specs.keySet();
    }

    /** Spec lookup for callers that need the specialist's limits (e.g. the daemon budget). */
    public java.util.Optional<SubAgentSpec> spec(String specialistType) {
        return java.util.Optional.ofNullable(specs.get(specialistType));
    }

    /** Spring AI's aggregate drops empty-usage rounds, so completeness must be checked before aggregation. */
    private static final class UsageTrackingModel implements ChatModel {
        private final ChatModel delegate;
        private volatile boolean complete = true;

        UsageTrackingModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return delegate.getOptions();
        }

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            var response = delegate.call(prompt);
            if (!SubAgentResult.from(response).usageReported()) complete = false;
            return response;
        }
    }

    /**
     * Build chat options for sub-agent. Skips model override when tier-based resolution
     * was used (model already resolved from DB via role).
     */
    private OpenAiChatOptions.Builder buildSubAgentOptions(SubAgentSpec spec, int maxTokensCap) {
        int maxTokens = maxTokensCap > 0 ? Math.min(spec.maxTokens(), maxTokensCap) : spec.maxTokens();
        var builder = OpenAiChatOptions.builder()
                .maxTokens(maxTokens)
                .temperature(spec.temperature());
        // Only set model from YAML if no tier-based resolution (avoids overriding DB model)
        boolean tierResolved = spec.tier() != null && !spec.tier().isBlank();
        if (!tierResolved && spec.model() != null && !spec.model().isBlank()) {
            builder.model(spec.model());
        }
        return builder;
    }

    /** The requested specialist is not defined (raised before any LLM call). */
    public static class UnknownSpecialistException extends IllegalArgumentException {
        public UnknownSpecialistException(String specialist) {
            super("Unknown specialist type: " + specialist);
        }
    }

    public static class SubAgentTimeoutException extends RuntimeException {
        public SubAgentTimeoutException(String specialist, long timeoutSeconds) {
            super("Sub-agent '%s' timed out after %d seconds".formatted(specialist, timeoutSeconds));
        }
    }
}

package ai.kukuvaia.agentrun;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.agent.PersonaSpec;
import ai.kukuvaia.memory.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Lifecycle orchestrator for non-interactive agent runs (P23 §Design/2).
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #enqueue(AgentRunSpec)} — inserts a {@code queued} row, schedules execution
 *       on the agent-run executor, returns the run id immediately. For async callers
 *       (POST /api/agent-runs).</li>
 *   <li>{@link #runSync(AgentRunSpec)} — inserts and executes inline; returns the final
 *       {@link AgentRun} record. For sync callers (POST /api/agent-runs/run).</li>
 * </ul>
 *
 * <p>Persona resolution: when {@link AgentRunSpec#personaName()} is set, the system prompt is
 * pulled from {@link PersonaService}; otherwise {@link LlmFollowupSpec#systemPrompt()} is used
 * verbatim. If both are blank and {@code skipIfEmpty} is true the run is marked skipped — never
 * dispatched with an empty system prompt (avoids accidental defaults from the bare LLM).
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final AgentRunRepository repository;
    private final ChatClient chatClient;
    private final EmbeddingService embeddingService;
    private final PersonaService personaService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final AgentRunConfig.AgentRunProperties properties;

    private final ExpressionParser spel = new SpelExpressionParser();

    public AgentRunService(AgentRunRepository repository,
                           @Qualifier("agentRunChatClient") ChatClient chatClient,
                           EmbeddingService embeddingService,
                           PersonaService personaService,
                           ObjectMapper objectMapper,
                           @Qualifier("agentRunExecutor") Executor executor,
                           AgentRunConfig.AgentRunProperties properties) {
        this.repository = repository;
        this.chatClient = chatClient;
        this.embeddingService = embeddingService;
        this.personaService = personaService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.properties = properties;
    }

    public UUID enqueue(AgentRunSpec spec) {
        UUID id = repository.insertQueued(spec);
        CompletableFuture.runAsync(() -> safeExecute(id, spec), executor);
        return id;
    }

    public AgentRun runSync(AgentRunSpec spec) {
        UUID id = repository.insertQueued(spec);
        safeExecute(id, spec);
        return repository.findById(id).orElseThrow(() ->
                new IllegalStateException("Run row vanished after execute: " + id));
    }

    public Optional<AgentRun> findById(UUID id) {
        return repository.findById(id);
    }

    public List<AgentRun> findByInvokerName(String invokerName, int limit) {
        return repository.findByInvokerName(invokerName, limit);
    }

    // --- internals -------------------------------------------------------

    private void safeExecute(UUID id, AgentRunSpec spec) {
        try {
            execute(id, spec);
        } catch (Exception e) {
            log.error("agent_run id={} failed: {}", id, e.getMessage(), e);
            repository.markFailed(id, e.getClass().getSimpleName(),
                    truncate(e.getMessage(), 4000));
        }
    }

    private void execute(UUID id, AgentRunSpec spec) {
        repository.markInProgress(id);

        LlmFollowupSpec followup = spec.llmFollowup();
        if (followup == null) {
            // Pure tool/data run with no LLM — persist input as output verbatim.
            String text = stringifyInput(spec.input());
            float[] embedding = maybeEmbed(true, text);
            repository.markCompleted(id, spec.input(), text, embedding, null, null, null);
            return;
        }

        String systemPrompt = resolveSystemPrompt(spec, followup);
        String userPrompt = resolveUserPrompt(spec, followup);

        if (followup.skipIfEmpty() && (userPrompt == null || userPrompt.isBlank())) {
            log.info("agent_run id={} skipped: empty user prompt + skipIfEmpty=true", id);
            repository.markSkipped(id, "empty user prompt");
            return;
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            log.info("agent_run id={} skipped: empty system prompt (no persona, no instructions)", id);
            repository.markSkipped(id, "empty system prompt");
            return;
        }

        if (followup.similarityContext().enabled() && embeddingService.isAvailable()) {
            String prefix = buildSimilarityPrefix(spec, followup, userPrompt);
            if (!prefix.isBlank()) {
                userPrompt = prefix + "\n\n" + userPrompt;
            }
        }

        String modelToCall = preferredModel(followup);
        ChatResponse response = callLlm(systemPrompt, userPrompt, modelToCall);
        TokenUsage usage = readUsage(response);
        String responseText = readText(response);
        String resolvedModel = readModel(response, modelToCall);

        // Optional one-step escalation per P23 §Design/6.
        if (followup.modelPreference() != null
                && followup.modelPreference().hasEscalation()
                && properties.getMaxEscalationDepth() > 0) {
            JsonNode parsed = parseJsonOutput(responseText);
            if (evaluateEscalationTrigger(followup.modelPreference().escalationTrigger(), parsed, usage)) {
                String escalateTo = followup.modelPreference().escalateTo();
                log.info("agent_run id={} escalating {} -> {}", id, resolvedModel, escalateTo);
                ChatResponse second = callLlm(systemPrompt, userPrompt, escalateTo);
                TokenUsage usage2 = readUsage(second);
                responseText = readText(second);
                resolvedModel = readModel(second, escalateTo);
                usage = usage.plus(usage2);
            }
        }

        JsonNode outputJson = parseJsonOutput(responseText);
        if (outputJson == null) {
            // Wrap plain text so the JSONB column always carries a structured shape.
            ObjectNode wrap = JsonNodeFactory.instance.objectNode();
            wrap.put("text", responseText == null ? "" : responseText);
            outputJson = wrap;
        }

        float[] embedding = maybeEmbed(followup.storeEmbedding(), responseText);
        repository.markCompleted(id, outputJson, responseText, embedding,
                resolvedModel, usage.promptTokens(), usage.completionTokens());
    }

    private String resolveSystemPrompt(AgentRunSpec spec, LlmFollowupSpec followup) {
        if (spec.personaName() != null && !spec.personaName().isBlank()) {
            try {
                PersonaSpec persona = personaService.allPersonas().get(spec.personaName());
                if (persona == null) {
                    throw new IllegalArgumentException("Unknown persona: " + spec.personaName());
                }
                String base = persona.systemPrompt();
                String override = followup.systemPrompt();
                if (override != null && !override.isBlank()) {
                    // Override is appended so the persona's invariants stay intact and the caller
                    // can layer per-run instructions on top.
                    return base + "\n\n" + override;
                }
                return base;
            } catch (IllegalArgumentException e) {
                throw e;
            }
        }
        return followup.systemPrompt();
    }

    private String resolveUserPrompt(AgentRunSpec spec, LlmFollowupSpec followup) {
        if (followup.userPromptTemplate() != null && !followup.userPromptTemplate().isBlank()) {
            return followup.userPromptTemplate();
        }
        return stringifyInput(spec.input());
    }

    private String stringifyInput(JsonNode input) {
        if (input == null) return "";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input);
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }

    private String buildSimilarityPrefix(AgentRunSpec spec, LlmFollowupSpec followup,
                                         String userPrompt) {
        SimilarityContextSpec sc = followup.similarityContext();
        float[] queryVec = embeddingService.embed(userPrompt);
        if (queryVec == null) return "";
        String filterInvoker = sc.filterByInvokerName() ? spec.invokerName() : null;
        List<AgentRun> hits = repository.findSimilar(queryVec, sc.topK(), sc.maxAgeDays(),
                filterInvoker, sc.filterByTags());
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Past similar runs (most recent first):\n");
        for (AgentRun h : hits) {
            sb.append("- [").append(h.createdAt()).append("] ")
                    .append(truncate(h.outputText(), 400)).append('\n');
        }
        return sb.toString();
    }

    private String preferredModel(LlmFollowupSpec followup) {
        ModelPreferenceSpec mp = followup.modelPreference();
        if (mp != null && mp.primary() != null && !mp.primary().isBlank()) {
            return mp.primary();
        }
        return null; // ChatClient default
    }

    private ChatResponse callLlm(String systemPrompt, String userPrompt, String modelOverride) {
        var prompt = chatClient.prompt().system(systemPrompt).user(userPrompt);
        // Model override is applied as an advisor param so ModelRoutingAdvisor can honor it
        // without adding a new ChatClient call surface.
        if (modelOverride != null && !modelOverride.isBlank()) {
            prompt = prompt.advisors(spec -> spec.param("kukuvaia.model.override", modelOverride));
        }
        return prompt.call().chatResponse();
    }

    private TokenUsage readUsage(ChatResponse response) {
        if (response == null) return TokenUsage.empty();
        var meta = response.getMetadata();
        if (meta == null || meta.getUsage() == null) return TokenUsage.empty();
        var u = meta.getUsage();
        Integer p = u.getPromptTokens() == null ? null : u.getPromptTokens().intValue();
        Integer c = u.getCompletionTokens() == null ? null : u.getCompletionTokens().intValue();
        return new TokenUsage(p, c);
    }

    private String readText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String t = response.getResult().getOutput().getText();
        return t == null ? "" : t;
    }

    private String readModel(ChatResponse response, String fallback) {
        if (response == null || response.getMetadata() == null) return fallback;
        String m = response.getMetadata().getModel();
        return m != null && !m.isBlank() ? m : fallback;
    }

    private JsonNode parseJsonOutput(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean evaluateEscalationTrigger(String expression, JsonNode parsedOutput,
                                              TokenUsage usage) {
        try {
            Map<String, Object> root = new HashMap<>();
            root.put("output", parsedOutput == null ? Map.of() : objectMapper.convertValue(parsedOutput, Map.class));
            root.put("status", "in_progress");
            root.put("prompt_tokens", usage.promptTokens());
            root.put("completion_tokens", usage.completionTokens());
            root.put("total_tokens", usage.totalTokens());
            StandardEvaluationContext ctx = new StandardEvaluationContext(root);
            // MapAccessor lets dot-notation work on Map values: `output.needs_escalation` instead
            // of `output['needs_escalation']`. SpEL's default property accessor only sees JavaBean
            // properties on the root.
            ctx.addPropertyAccessor(new MapAccessor());
            // Also surface as variables so `#output.foo` works for callers that prefer that form.
            root.forEach(ctx::setVariable);
            Expression expr = spel.parseExpression(expression);
            Object value = expr.getValue(ctx);
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            log.warn("Escalation trigger '{}' failed to evaluate, not escalating: {}",
                    expression, e.getMessage());
            return false;
        }
    }

    private float[] maybeEmbed(boolean enabled, String text) {
        if (!enabled || text == null || text.isBlank()) return null;
        if (!embeddingService.isAvailable()) return null;
        try {
            return embeddingService.embed(text);
        } catch (Exception e) {
            log.warn("Embedding agent_run output failed: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record TokenUsage(Integer promptTokens, Integer completionTokens) {
        static TokenUsage empty() { return new TokenUsage(null, null); }

        Integer totalTokens() {
            int p = promptTokens == null ? 0 : promptTokens;
            int c = completionTokens == null ? 0 : completionTokens;
            return p + c;
        }

        TokenUsage plus(TokenUsage other) {
            return new TokenUsage(
                    sum(this.promptTokens, other.promptTokens),
                    sum(this.completionTokens, other.completionTokens));
        }

        private static Integer sum(Integer a, Integer b) {
            if (a == null && b == null) return null;
            return (a == null ? 0 : a) + (b == null ? 0 : b);
        }
    }
}

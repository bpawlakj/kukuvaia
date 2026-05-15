package ai.kukuvaia.agent;

import ai.kukuvaia.advisors.ModelRoutingAdvisor;
import ai.kukuvaia.memory.extraction.MemoryExtractionService;
import ai.kukuvaia.memory.repository.SessionRepository;
import ai.kukuvaia.output.MetadataBlock;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.SessionOutputSink;
import ai.kukuvaia.output.SpanEventEmitter;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.tools.PlanningTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Chat orchestration: receives message, routes through ChatClient, streams OutputBlocks.
 * After each successful chat, triggers async memory extraction in the background.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String USER_ID = "bartek"; // TODO: resolve from auth context

    /** Key used to ship the current planning phase to the CLI in a MetadataBlock. */
    public static final String PLANNING_PHASE_KEY = "kukuvaia.planning.phase";

    private final ChatClient chatClient;
    private final PersonaService personaService;
    private final MemoryExtractionService extractionService;
    private final SessionRepository sessionRepository;
    private final Executor extractionExecutor;
    private final SessionOutputSink outputSink;
    private final SpanEventEmitter spanEmitter;
    private final PlanningModeService planningModeService;

    @Value("${kukuvaia.chat.timeout-seconds:180}")
    private int chatTimeoutSeconds;

    public AgentService(ChatClient chatClient,
                        PersonaService personaService,
                        MemoryExtractionService extractionService,
                        SessionRepository sessionRepository,
                        @Qualifier("memoryExtractionExecutor") Executor extractionExecutor,
                        SessionOutputSink outputSink,
                        SpanEventEmitter spanEmitter,
                        PlanningModeService planningModeService) {
        this.chatClient = chatClient;
        this.personaService = personaService;
        this.extractionService = extractionService;
        this.sessionRepository = sessionRepository;
        this.extractionExecutor = extractionExecutor;
        this.outputSink = outputSink;
        this.spanEmitter = spanEmitter;
        this.planningModeService = planningModeService;
    }

    /**
     * Stream a chat response as OutputBlocks via the per-session reactive sink.
     * Chat content plus any concurrently-emitted blocks (span events, progress, etc.)
     * flow through the same stream. Errors are turned into error {@link TextBlock}s.
     * Sink is completed after the content is emitted so the caller's {@link Flux} terminates cleanly.
     */
    public Flux<OutputBlock> streamChat(String sessionId, String message) {
        log.info("Chat request: sessionId={}, messageLength={}", sessionId, message.length());

        Flux<OutputBlock> stream = outputSink.streamFor(sessionId);
        // Dispatch the chat work on a worker thread so the HTTP subscriber
        // attaches to the sink before emissions and completion happen.
        CompletableFuture.runAsync(() -> runChat(sessionId, message));
        return stream;
    }

    private void runChat(String sessionId, String message) {
        long turnStart = System.currentTimeMillis();
        log.info("[runChat] ===== sessionId={} turn start — message length={} =====",
                sessionId, message.length());
        log.debug("[runChat] sessionId={} message preview: {}", sessionId,
                message.length() > 200 ? message.substring(0, 200) + "…" : message);
        log.info("[runChat] sessionId={} planning state: inPlanningMode={} phase={}",
                sessionId,
                planningModeService.isInPlanningMode(sessionId),
                planningModeService.getSession(sessionId).map(s -> s.phase().name()).orElse("-"));

        SpanEventEmitter.Handle supervisorSpan = spanEmitter.start(sessionId, null,
                "role:supervisor",
                java.util.Map.of("kukuvaia.specialist", "supervisor"));
        String status = "success";
        try {
            sessionRepository.ensureExists(sessionId, USER_ID);
            PersonaSpec persona = personaService.getActivePersona(sessionId);
            log.info("[runChat] sessionId={} persona active: name={} (systemPrompt {} chars)",
                    sessionId, persona.name(),
                    persona.systemPrompt() != null ? persona.systemPrompt().length() : 0);

            long llmStart = System.currentTimeMillis();
            log.info("[runChat] sessionId={} dispatching to ChatClient...", sessionId);
            var chatResponse = CompletableFuture.supplyAsync(() -> {
                PlanningTools.setContext(sessionId, USER_ID);
                try {
                    return chatClient.prompt()
                            .system(persona.systemPrompt())
                            .user(message)
                            .advisors(spec -> spec
                                    .param("chat_memory_conversation_id", sessionId)
                                    .param("kukuvaia.userId", USER_ID))
                            .call()
                            .chatResponse();
                } finally {
                    PlanningTools.clearContext();
                }
            }).get(chatTimeoutSeconds, TimeUnit.SECONDS);
            long llmElapsed = System.currentTimeMillis() - llmStart;
            log.info("[runChat] sessionId={} ChatClient returned in {}ms (null={}) — routing decision={} routedModel={}",
                    sessionId, llmElapsed,
                    chatResponse == null,
                    ModelRoutingAdvisor.lastDecision(),
                    ModelRoutingAdvisor.lastRoutedModel());

            String response = extractResponseText(chatResponse, sessionId);
            log.info("[runChat] sessionId={} extracted response ({} chars) — emitting TextBlock",
                    sessionId, response != null ? response.length() : 0);
            outputSink.emit(sessionId, new TextBlock(response, null));
            triggerExtraction(USER_ID, sessionId);
        } catch (TimeoutException e) {
            status = "timeout";
            log.error("[runChat] sessionId={} TIMEOUT after {}s — likely stuck in tool-call loop or slow provider",
                    sessionId, chatTimeoutSeconds);
            outputSink.emit(sessionId, new TextBlock(
                    "Request timed out after %ds. The agent may be stuck in a tool-calling loop. Try a simpler prompt."
                            .formatted(chatTimeoutSeconds), "error"));
        } catch (Exception e) {
            status = "error";
            log.error("[runChat] sessionId={} CHAT FAILED: {}: {}",
                    sessionId, e.getClass().getSimpleName(), e.getMessage(), e);
            outputSink.emit(sessionId, new TextBlock(sanitizeError(e), "error"));
        } finally {
            long turnElapsed = System.currentTimeMillis() - turnStart;
            log.info("[runChat] sessionId={} turn finished in {}ms — status={}", sessionId, turnElapsed, status);
            // Emit planning phase LAST (before span end + sink complete) so the
            // CLI's chatResultMsg sees the authoritative phase regardless of
            // whether the turn succeeded, timed out, or errored.
            emitPlanningPhaseIfActive(sessionId);

            java.util.Map<String, Object> endAttrs = new java.util.LinkedHashMap<>();
            endAttrs.put("kukuvaia.status", status);
            // Routing decision and routed model are surfaced so the CLI tracker
            // shows "supervisor (ESCALATE → opus)" instead of a blind "supervisor".
            if (ModelRoutingAdvisor.lastDecision() != null) {
                endAttrs.put("kukuvaia.routing.decision", ModelRoutingAdvisor.lastDecision());
            }
            if (ModelRoutingAdvisor.lastRoutedModel() != null) {
                endAttrs.put("kukuvaia.routing.model", ModelRoutingAdvisor.lastRoutedModel());
            }
            spanEmitter.end(supervisorSpan, endAttrs);
            ModelRoutingAdvisor.clearLast();
            outputSink.complete(sessionId);
        }
    }

    /**
     * Synchronous chat for command pipelines.
     */
    public String chat(String sessionId, String message) {
        PersonaSpec persona = personaService.getActivePersona(sessionId);

        return chatClient.prompt()
                .system(persona.systemPrompt())
                .user(message)
                .advisors(spec -> spec
                        .param("chat_memory_conversation_id", sessionId)
                        .param("kukuvaia.userId", USER_ID))
                .call()
                .content();
    }

    /**
     * Extracts the final response text from a ChatResponse.
     *
     * Some reasoning models (arcee-trinity-thinking, DeepSeek R1, GPT o1/o3)
     * place their chain-of-thought in a {@code reasoning_content} field and
     * leave the normal {@code content} empty. Spring AI exposes such extras
     * via the AssistantMessage metadata map. Order of preference:
     * <ol>
     *   <li>normal {@code content} (non-blank)</li>
     *   <li>any key that looks like reasoning / thinking / think in the
     *       output metadata (prefixed with a clarifying label)</li>
     *   <li>warning block letting the user know to pick a non-thinking model</li>
     * </ol>
     */
    private String extractResponseText(org.springframework.ai.chat.model.ChatResponse chatResponse,
                                       String sessionId) {
        // Header line so the full dump for one turn is easy to grep.
        log.info("[extract] ===== sessionId={} begin response analysis =====", sessionId);

        if (chatResponse == null) {
            log.warn("[extract] session={} ChatResponse is NULL — no response object returned by Spring AI", sessionId);
            return emptyResponseWarning();
        }

        // ChatResponse-level metadata (usage, model name actually used by the provider,
        // rate-limit headers etc.). Useful to confirm routing decisions and token counts.
        try {
            var responseMetadata = chatResponse.getMetadata();
            if (responseMetadata != null) {
                log.info("[extract] session={} chatResponse.metadata: id={} model={} usage={} rateLimit={}",
                        sessionId,
                        safe(responseMetadata.getId()),
                        safe(responseMetadata.getModel()),
                        responseMetadata.getUsage(),
                        responseMetadata.getRateLimit());
            } else {
                log.info("[extract] session={} chatResponse.metadata is null", sessionId);
            }
            log.info("[extract] session={} results count={}", sessionId,
                    chatResponse.getResults() != null ? chatResponse.getResults().size() : 0);
        } catch (Exception e) {
            log.warn("[extract] session={} failed to log chatResponse metadata: {}", sessionId, e.getMessage());
        }

        if (chatResponse.getResult() == null) {
            log.warn("[extract] session={} ChatResponse.getResult() is NULL — provider returned no choices", sessionId);
            return emptyResponseWarning();
        }

        var result = chatResponse.getResult();
        var output = result.getOutput();

        // Generation-level metadata — typically holds finishReason + provider-specific extras.
        try {
            var generationMetadata = result.getMetadata();
            if (generationMetadata != null) {
                log.info("[extract] session={} generation.metadata finishReason={} contentFilters={} full={}",
                        sessionId,
                        safe(generationMetadata.getFinishReason()),
                        generationMetadata.getContentFilters(),
                        generationMetadata);
            }
        } catch (Exception e) {
            log.warn("[extract] session={} failed to log generation metadata: {}", sessionId, e.getMessage());
        }

        if (output == null) {
            log.warn("[extract] session={} result.getOutput() is NULL", sessionId);
            return emptyResponseWarning();
        }

        // Tool calls — if the LLM issued a tool_calls response and the ToolCallAdvisor
        // either stopped the loop or the tool was filtered, content stays null.
        try {
            var toolCalls = output.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                log.warn("[extract] session={} AssistantMessage has {} tool call(s) with no final content — " +
                                "tool loop likely truncated (blocked tool / round limit / sampling cut). toolCalls={}",
                        sessionId, toolCalls.size(), toolCalls);
            }
            var media = output.getMedia();
            if (media != null && !media.isEmpty()) {
                log.info("[extract] session={} AssistantMessage has {} media item(s)", sessionId, media.size());
            }
        } catch (Exception e) {
            log.debug("[extract] session={} failed to read tool calls / media: {}", sessionId, e.getMessage());
        }

        String text = output.getText();
        log.info("[extract] session={} content length={} blank={}",
                sessionId,
                text != null ? text.length() : -1,
                text == null || text.isBlank());

        if (text != null && !text.isBlank()) {
            log.info("[extract] session={} happy path — returning content ({} chars)", sessionId, text.length());
            log.info("[extract] ===== sessionId={} end response analysis =====", sessionId);
            return text;
        }

        // content is empty — dump EVERY metadata key and value so we can see what the
        // provider actually returned. No filtering, no truncation beyond toString().
        var metadata = output.getMetadata();
        if (metadata == null) {
            log.warn("[extract] session={} AssistantMessage metadata is NULL and content is empty — " +
                    "nothing to surface.", sessionId);
            log.info("[extract] ===== sessionId={} end response analysis =====", sessionId);
            return emptyResponseWarning();
        }

        log.warn("[extract] session={} empty content. Metadata keys ({}): {}",
                sessionId, metadata.size(), metadata.keySet());
        for (var entry : metadata.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String valuePreview = value == null ? "null" : truncateForLog(value.toString(), 500);
            log.warn("[extract] session={} metadata[{}] type={} value={}",
                    sessionId,
                    key,
                    value != null ? value.getClass().getSimpleName() : "null",
                    valuePreview);
        }

        // Refusal is a first-class Spring AI field on some models. If present, the
        // model explicitly refused — content will always be empty.
        Object refusal = metadata.get("refusal");
        if (refusal != null && !refusal.toString().isBlank()) {
            log.warn("[extract] session={} REFUSAL from model: {}", sessionId, refusal);
        }
        Object finishReason = metadata.get("finishReason");
        if (finishReason != null) {
            log.warn("[extract] session={} finishReason={} (look up OpenAI finish_reason semantics — " +
                    "tool_calls/length/content_filter explain empty content)", sessionId, finishReason);
        }

        for (var key : new String[] {
                "reasoningContent", "reasoning_content",
                "reasoning", "thinking", "think", "thought"
        }) {
            Object val = metadata.get(key);
            if (val != null && !val.toString().isBlank()) {
                log.info("[extract] session={} reasoning-only output detected (metadata.{} present, {} chars). " +
                                "Surfacing as 💭 preview.",
                        sessionId, key, val.toString().length());
                log.info("[extract] ===== sessionId={} end response analysis =====", sessionId);
                return "💭 " + val;
            }
        }

        log.warn("[extract] session={} NO content, NO reasoning field — returning empty-response warning to user.",
                sessionId);
        log.info("[extract] ===== sessionId={} end response analysis =====", sessionId);
        return emptyResponseWarning();
    }

    private static String safe(Object o) {
        return o == null ? "(null)" : o.toString();
    }

    private static String truncateForLog(String s, int max) {
        if (s == null) return "null";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…[" + s.length() + " chars total]";
    }

    /**
     * Ship the current planning phase to the CLI as a MetadataBlock so the
     * toolbar / yellow border stays consistent with the server state machine
     * regardless of how the phase was advanced (LLM tool call, approval trigger,
     * explicit slash command). The CLI treats this as authoritative and syncs
     * its local {@code planToolbar} accordingly. Best-effort — a missing or
     * failed emit never blocks the chat reply.
     */
    private void emitPlanningPhaseIfActive(String sessionId) {
        try {
            planningModeService.getSession(sessionId).ifPresent(session -> {
                String phase = session.phase().name().toLowerCase();
                outputSink.emit(sessionId, new MetadataBlock(
                        java.util.Map.of(PLANNING_PHASE_KEY, phase)));
                log.debug("Emitted planning phase metadata: sessionId={} phase={}", sessionId, phase);
            });
        } catch (Exception e) {
            log.debug("emitPlanningPhaseIfActive failed for sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private String emptyResponseWarning() {
        return "⚠️ The routed model returned no visible content. If you used a reasoning " +
                "model (arcee-trinity-thinking, DeepSeek R1, GPT o1), its output may have " +
                "landed in a reasoning field. Consider a non-thinking advisor model or " +
                "rephrase the request without escalation triggers.";
    }

    private void triggerExtraction(String userId, String sessionId) {
        CompletableFuture.runAsync(
                () -> extractionService.extract(userId, sessionId),
                extractionExecutor
        ).exceptionally(ex -> {
            log.warn("Async memory extraction failed: {}", ex.getMessage());
            return null;
        });
    }

    private String sanitizeError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "Unexpected error";
        // 408 + litellm.Timeout — Sanoma SmartGate's LiteLLM proxy enforces a hard 10s timeout
        // per upstream call. Big tool-result payloads (e.g. 50+ validation rules per template)
        // routinely exceed it. Surface this clearly so the operator knows to ask for a smaller
        // slice rather than re-trying the same prompt.
        if (msg.contains("litellm.Timeout") || msg.contains("408 Request Timeout")) {
            return "LLM proxy timed out (SmartGate's 10s budget). Likely too much tool data in "
                    + "this turn — ask for a smaller slice, e.g. 'first 10 rules' or 'next page'.";
        }
        if (msg.contains("Timeout") || msg.contains("timed out")) {
            return "Request timed out. The LLM provider did not respond in time. Try again or use a simpler prompt.";
        }
        if (msg.contains("401") || msg.contains("403")) {
            return "Authentication failed. Check your LLM provider credentials.";
        }
        if (msg.contains("429")) {
            return "Rate limited by LLM provider. Wait a moment and try again.";
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return "LLM error: " + msg;
    }
}

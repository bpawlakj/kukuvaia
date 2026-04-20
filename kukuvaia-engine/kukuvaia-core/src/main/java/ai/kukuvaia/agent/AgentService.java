package ai.kukuvaia.agent;

import ai.kukuvaia.advisors.ModelRoutingAdvisor;
import ai.kukuvaia.memory.extraction.MemoryExtractionService;
import ai.kukuvaia.memory.repository.SessionRepository;
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

    private final ChatClient chatClient;
    private final PersonaService personaService;
    private final MemoryExtractionService extractionService;
    private final SessionRepository sessionRepository;
    private final Executor extractionExecutor;
    private final SessionOutputSink outputSink;
    private final SpanEventEmitter spanEmitter;

    @Value("${kukuvaia.chat.timeout-seconds:180}")
    private int chatTimeoutSeconds;

    public AgentService(ChatClient chatClient,
                        PersonaService personaService,
                        MemoryExtractionService extractionService,
                        SessionRepository sessionRepository,
                        @Qualifier("memoryExtractionExecutor") Executor extractionExecutor,
                        SessionOutputSink outputSink,
                        SpanEventEmitter spanEmitter) {
        this.chatClient = chatClient;
        this.personaService = personaService;
        this.extractionService = extractionService;
        this.sessionRepository = sessionRepository;
        this.extractionExecutor = extractionExecutor;
        this.outputSink = outputSink;
        this.spanEmitter = spanEmitter;
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
        SpanEventEmitter.Handle supervisorSpan = spanEmitter.start(sessionId, null,
                "role:supervisor",
                java.util.Map.of("kukuvaia.specialist", "supervisor"));
        String status = "success";
        try {
            sessionRepository.ensureExists(sessionId, USER_ID);
            PersonaSpec persona = personaService.getActivePersona(sessionId);

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

            String response = extractResponseText(chatResponse, sessionId);
            outputSink.emit(sessionId, new TextBlock(response, null));
            triggerExtraction(USER_ID, sessionId);
        } catch (TimeoutException e) {
            status = "timeout";
            log.error("Chat timed out after {}s: sessionId={}", chatTimeoutSeconds, sessionId);
            outputSink.emit(sessionId, new TextBlock(
                    "Request timed out after %ds. The agent may be stuck in a tool-calling loop. Try a simpler prompt."
                            .formatted(chatTimeoutSeconds), "error"));
        } catch (Exception e) {
            status = "error";
            log.error("Chat failed: sessionId={}, error={}", sessionId, e.getMessage());
            outputSink.emit(sessionId, new TextBlock(sanitizeError(e), "error"));
        } finally {
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
        if (chatResponse == null || chatResponse.getResult() == null) {
            log.warn("ChatResponse is null/empty for session {}", sessionId);
            return emptyResponseWarning();
        }
        var output = chatResponse.getResult().getOutput();
        String text = output.getText();
        if (text != null && !text.isBlank()) {
            return text;
        }

        // content is empty — look for reasoning-like fields in metadata.
        var metadata = output.getMetadata();
        if (metadata != null) {
            for (var key : new String[] {
                    "reasoningContent", "reasoning_content",
                    "reasoning", "thinking", "think"
            }) {
                Object val = metadata.get(key);
                if (val != null && !val.toString().isBlank()) {
                    log.info("Session {} — routed model returned reasoning-only output " +
                            "(content empty, metadata.{} present). Surfacing reasoning.",
                            sessionId, key);
                    return "💭 " + val;
                }
            }
            log.warn("Session {} — empty content and no reasoning field found. Metadata keys: {}",
                    sessionId, metadata.keySet());
        }
        return emptyResponseWarning();
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

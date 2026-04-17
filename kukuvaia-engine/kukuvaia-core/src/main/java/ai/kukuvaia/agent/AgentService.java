package ai.kukuvaia.agent;

import ai.kukuvaia.memory.extraction.MemoryExtractionService;
import ai.kukuvaia.memory.repository.SessionRepository;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.output.OutputBlock;
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

    @Value("${kukuvaia.chat.timeout-seconds:180}")
    private int chatTimeoutSeconds;

    public AgentService(ChatClient chatClient,
                        PersonaService personaService,
                        MemoryExtractionService extractionService,
                        SessionRepository sessionRepository,
                        @Qualifier("memoryExtractionExecutor") Executor extractionExecutor) {
        this.chatClient = chatClient;
        this.personaService = personaService;
        this.extractionService = extractionService;
        this.sessionRepository = sessionRepository;
        this.extractionExecutor = extractionExecutor;
    }

    /**
     * Stream a chat response as OutputBlocks.
     * Errors are caught and returned as error TextBlocks — never propagated to Tomcat SSE handler.
     * After a successful response, fires async memory extraction.
     */
    public Flux<OutputBlock> streamChat(String sessionId, String message) {
        log.info("Chat request: sessionId={}, messageLength={}", sessionId, message.length());

        try {
            sessionRepository.ensureExists(sessionId, USER_ID);
            PersonaSpec persona = personaService.getActivePersona(sessionId);

            String response = CompletableFuture.supplyAsync(() -> {
                PlanningTools.setContext(sessionId, USER_ID);
                try {
                    return chatClient.prompt()
                            .system(persona.systemPrompt())
                            .user(message)
                            .advisors(spec -> spec
                                    .param("chat_memory_conversation_id", sessionId)
                                    .param("kukuvaia.userId", USER_ID))
                            .call()
                            .content();
                } finally {
                    PlanningTools.clearContext();
                }
            }).get(chatTimeoutSeconds, TimeUnit.SECONDS);

            // Fire async memory extraction after successful chat
            triggerExtraction(USER_ID, sessionId);

            return Flux.just(new TextBlock(response, null));
        } catch (TimeoutException e) {
            log.error("Chat timed out after {}s: sessionId={}", chatTimeoutSeconds, sessionId);
            return Flux.just(new TextBlock(
                    "Request timed out after %ds. The agent may be stuck in a tool-calling loop. Try a simpler prompt."
                            .formatted(chatTimeoutSeconds), "error"));
        } catch (Exception e) {
            log.error("Chat failed: sessionId={}, error={}", sessionId, e.getMessage());
            String errorMsg = sanitizeError(e);
            return Flux.just(new TextBlock(errorMsg, "error"));
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

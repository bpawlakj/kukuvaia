package ai.kukuvaia.api;

import ai.kukuvaia.agent.CommandRouter;
import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * POST /api/chat — SSE stream of OutputBlocks.
 * Routes through CommandRouter (slash commands → deterministic, free text → ChatClient).
 * Errors are caught and returned as error TextBlocks to prevent Tomcat SSE handler crashes.
 *
 * <p>If the request carries a {@code persona}, the session is (re)bound to that persona BEFORE
 * routing — so the env-var {@code KUKUVAIA_PERSONA} pushed by every CLI request actually shapes
 * the tool whitelist for this turn. Unknown persona is surfaced as a single error TextBlock
 * rather than a 500: a typo in the env var must be visible to the operator.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final CommandRouter commandRouter;
    private final PersonaService personaService;

    @Value("${kukuvaia.chat.timeout-seconds:180}")
    private int chatTimeoutSeconds;

    public ChatController(CommandRouter commandRouter, PersonaService personaService) {
        this.commandRouter = commandRouter;
        this.personaService = personaService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<OutputBlock> chat(@RequestBody ChatRequest request) {
        try {
            personaService.applyIfPresent(request.sessionId(), request.persona());
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting chat: unknown persona '{}' for session {}", request.persona(), request.sessionId());
            return Flux.just(new TextBlock("Unknown persona: " + request.persona(), "error"));
        }

        // Backstop sits 30s past the inner AgentService timeout so the inner timeout's terminal
        // emit + sink-complete have headroom to land before this outer timeout fires. A tight 10s
        // margin race-conditioned with CompletableFuture.get(180s) under load — the inner timeout
        // would expire, the catch block would start emitting, and this outer timeout would still
        // win before the terminal signal reached the sink, leaving the CLI with a bare
        // 'sinkManyEmitterProcessor timeout' instead of the proper error TextBlock.
        return commandRouter.route(request.message(), request.sessionId())
                .timeout(Duration.ofSeconds(chatTimeoutSeconds + 30))
                .onErrorResume(e -> {
                    log.error("SSE stream error: {}", e.getMessage());
                    return Flux.just(new TextBlock("Error: " + e.getMessage(), "error"));
                });
    }
}

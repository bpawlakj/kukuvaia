package ai.kukuvaia.api;

import ai.kukuvaia.agent.CommandRouter;
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
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final CommandRouter commandRouter;

    @Value("${kukuvaia.chat.timeout-seconds:180}")
    private int chatTimeoutSeconds;

    public ChatController(CommandRouter commandRouter) {
        this.commandRouter = commandRouter;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<OutputBlock> chat(@RequestBody ChatRequest request) {
        return commandRouter.route(request.message(), request.sessionId())
                .timeout(Duration.ofSeconds(chatTimeoutSeconds + 10)) // backstop beyond AgentService timeout
                .onErrorResume(e -> {
                    log.error("SSE stream error: {}", e.getMessage());
                    return Flux.just(new TextBlock("Error: " + e.getMessage(), "error"));
                });
    }
}

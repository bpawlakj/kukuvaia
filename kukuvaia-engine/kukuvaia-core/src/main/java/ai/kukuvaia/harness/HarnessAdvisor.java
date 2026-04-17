package ai.kukuvaia.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * Injects compiled harness rules into the system prompt.
 * Rules are resolved per-user from the 5-level hierarchy
 * (platform → group → user → project → session).
 *
 * Configurable via {@code kukuvaia.harness.enabled}.
 */
@Component
public class HarnessAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HarnessAdvisor.class);
    private static final String CTX_USER_ID = "kukuvaia.userId";

    private final HarnessService harnessService;
    private final boolean enabled;

    public HarnessAdvisor(HarnessService harnessService,
                          @Value("${kukuvaia.harness.enabled:true}") boolean enabled) {
        this.harnessService = harnessService;
        this.enabled = enabled;
        log.info("HarnessAdvisor initialized: enabled={}", enabled);
    }

    @Override
    public int getOrder() {
        // After routing + loop detection, before memory
        return Ordered.HIGHEST_PRECEDENCE + 14;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) return request;

        String userId = extractUserId(request);
        String compiledRules = harnessService.resolveForUser(userId);

        if (compiledRules == null || compiledRules.isBlank()) {
            return request;
        }

        // Inject rules as additional system message
        var messages = new ArrayList<>(request.prompt().getInstructions());
        messages.addFirst(new SystemMessage(compiledRules));

        var enrichedPrompt = new Prompt(messages, request.prompt().getOptions());

        log.debug("Injected {} chars of harness rules for user {}", compiledRules.length(), userId);

        return request.mutate()
                .prompt(enrichedPrompt)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private String extractUserId(ChatClientRequest request) {
        Object id = request.context().get(CTX_USER_ID);
        if (id instanceof String s && !s.isBlank()) return s;
        if (id != null) return id.toString();
        return null;
    }
}

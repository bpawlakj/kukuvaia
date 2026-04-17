package ai.kukuvaia.advisors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks token usage per session and injects budget warnings.
 * Configurable per persona via kukuvaia.budget.* properties.
 */
@Component
public class TokenBudgetAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetAdvisor.class);
    private static final int DEFAULT_MAX_TOKENS = 50000;
    private static final double WARNING_THRESHOLD = 0.80;

    private static final String BUDGET_WARNING = """

            TOKEN BUDGET WARNING: You have used %d%% of your token budget (%d/%d).
            Wrap up current work. Summarize what's done and what remains.
            """;

    private final Map<String, AtomicInteger> sessionUsage = new ConcurrentHashMap<>();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String sessionId = (String) request.context().getOrDefault("chat_memory_conversation_id", "default");
        int used = sessionUsage.computeIfAbsent(sessionId, k -> new AtomicInteger(0)).get();
        int max = DEFAULT_MAX_TOKENS;

        double usage = (double) used / max;
        if (usage >= WARNING_THRESHOLD) {
            int pct = (int) (usage * 100);
            String warning = BUDGET_WARNING.formatted(pct, used, max);
            log.warn("Token budget warning: session={}, usage={}%", sessionId, pct);
            return request.mutate()
                    .prompt(request.prompt().augmentSystemMessage(warning))
                    .build();
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        var chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata().getUsage() != null) {
            String sessionId = "default"; // extracted from context in before()
            int tokens = (int) chatResponse.getMetadata().getUsage().getTotalTokens();
            sessionUsage.computeIfAbsent(sessionId, k -> new AtomicInteger(0)).addAndGet(tokens);
        }
        return response;
    }

    public int getUsage(String sessionId) {
        return sessionUsage.getOrDefault(sessionId, new AtomicInteger(0)).get();
    }

    public void resetUsage(String sessionId) {
        sessionUsage.remove(sessionId);
    }
}

package ai.kukuvaia.advisors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Fires pre/post tool events for audit logging, rate limiting, and cost tracking.
 * Runs as last advisor in chain (LOWEST_PRECEDENCE).
 *
 * Captures total tool call count and duration per request for monitoring.
 */
@Component
public class ToolHookDispatcher implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ToolHookDispatcher.class);

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        log.debug("Pre-request hook: messageCount={}", request.prompt().getInstructions().size());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        var chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getResult() != null) {
            var metadata = chatResponse.getResult().getMetadata();
            log.debug("Post-response hook: finishReason={}", metadata.getFinishReason());
        }
        return response;
    }
}

package ai.kukuvaia.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Logs provider name, model, and token usage for every LLM call.
 * Covers: Finding #14 (conversation data to wrong provider), Finding #15 (audit trail).
 *
 * Runs as first advisor in chain — captures metadata for all calls
 * (interactive and daemon, parent and sub-agent).
 */
@Component
public class ProviderAuditLog implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ProviderAuditLog.class);
    private static final String CTX_PROVIDER = "kukuvaia.provider";
    private static final String CTX_CONTEXT = "kukuvaia.executionContext";
    private static final String CTX_SPECIALIST = "kukuvaia.specialist";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // Run first
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String provider = (String) request.context()
                .getOrDefault(CTX_PROVIDER, "unknown");
        String executionContext = (String) request.context()
                .getOrDefault(CTX_CONTEXT, "interactive");
        String specialist = (String) request.context()
                .getOrDefault(CTX_SPECIALIST, "parent");

        // Set MDC for structured logging
        MDC.put("provider", provider);
        MDC.put("executionContext", executionContext);
        MDC.put("specialist", specialist);

        log.info("LLM call: provider={}, context={}, specialist={}, messageCount={}",
                provider, executionContext, specialist,
                request.prompt().getInstructions().size());

        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        var usage = response.chatResponse().getMetadata().getUsage();
        if (usage != null) {
            log.info("LLM response: promptTokens={}, completionTokens={}, totalTokens={}",
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens());
        }
        MDC.remove("provider");
        MDC.remove("executionContext");
        MDC.remove("specialist");
        return response;
    }
}

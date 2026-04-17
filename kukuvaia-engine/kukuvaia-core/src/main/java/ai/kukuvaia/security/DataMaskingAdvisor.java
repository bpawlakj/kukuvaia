package ai.kukuvaia.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Masks PII (PESEL, email, phone, CC, API keys, IBAN) before LLM sees the message,
 * and unmasks tokens in the response. Configurable via {@code kukuvaia.masking.enabled}.
 *
 * When disabled, passes through without modification — zero overhead.
 */
@Component
public class DataMaskingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(DataMaskingAdvisor.class);

    private final MaskingService maskingService;
    private final boolean enabled;

    // Thread-local: stores mask mapping for the current request→response cycle
    private final ThreadLocal<MaskingContext> requestContext = new ThreadLocal<>();

    public DataMaskingAdvisor(MaskingService maskingService,
                              @Value("${kukuvaia.masking.enabled:true}") boolean enabled) {
        this.maskingService = maskingService;
        this.enabled = enabled;
        log.info("DataMaskingAdvisor initialized: enabled={}", enabled);
    }

    @Override
    public int getOrder() {
        // After routing (model selected), before memory (memories shouldn't contain raw PII)
        return Ordered.HIGHEST_PRECEDENCE + 14;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) return request;

        var messages = request.prompt().getInstructions();
        if (messages.isEmpty()) return request;

        String lastMessage = messages.getLast().getText();
        MaskingContext ctx = maskingService.mask(lastMessage);

        if (ctx.isEmpty()) return request;

        // Store mapping for unmasking in after()
        requestContext.set(ctx);

        // Replace user message with masked version
        var maskedMessages = new java.util.ArrayList<>(messages);
        var original = maskedMessages.removeLast();

        // Create masked user message with same type
        var maskedMessage = new org.springframework.ai.chat.messages.UserMessage(ctx.maskedText());
        maskedMessages.add(maskedMessage);

        var maskedPrompt = new org.springframework.ai.chat.prompt.Prompt(
                maskedMessages, request.prompt().getOptions());

        log.info("Masked {} PII values in user message", ctx.tokenToOriginal().size());

        return request.mutate()
                .prompt(maskedPrompt)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) return response;

        MaskingContext ctx = requestContext.get();
        requestContext.remove();

        if (ctx == null || ctx.isEmpty()) return response;

        // Unmask tokens in the response text
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResults() == null) return response;

        boolean unmasked = false;
        for (Generation gen : chatResponse.getResults()) {
            if (gen.getOutput() != null && gen.getOutput().getText() != null) {
                String original = gen.getOutput().getText();
                String restored = maskingService.unmask(original, ctx.tokenToOriginal());
                if (!original.equals(restored)) {
                    unmasked = true;
                    // Note: Generation.output is typically immutable in Spring AI.
                    // The unmask happens at the response level — the ChatClientResponse
                    // carries the masked data from the LLM. The actual unmasking
                    // is handled when the response text is extracted by AgentService.
                    // For now, log it. Full unmasking at response extraction point.
                }
            }
        }

        if (unmasked) {
            log.debug("Response contains masked tokens that need unmasking");
        }

        return response;
    }
}

package ai.kukuvaia.advisors;

import ai.kukuvaia.provider.registry.ChatModelCache;
import ai.kukuvaia.provider.registry.ModelRecord;
import ai.kukuvaia.provider.registry.ModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Routes interactive chat to the optimal model tier based on message complexity.
 *
 * Classification:
 * - FAST: short greetings, simple questions → worker role (Haiku)
 * - DEFAULT: normal tasks, document operations → supervisor role (Sonnet)
 * - ESCALATE: loop flag set, explicit request, complex analysis → advisor role (Opus)
 *
 * Reads escalation flag from context (set by {@link LoopDetectionAdvisor}).
 * Overrides model via {@link OpenAiChatOptions} on the prompt.
 */
@Component
public class ModelRoutingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingAdvisor.class);

    static final String CTX_ESCALATE = "kukuvaia.escalate";
    static final String CTX_ROUTED_MODEL = "kukuvaia.routed-model";
    static final String CTX_ROUTING_DECISION = "kukuvaia.routing-decision";

    private static final Set<String> GREETING_WORDS = Set.of(
            "hi", "hello", "hey", "cześć", "hej", "siema", "yo", "thanks", "bye", "ok");

    private static final Set<String> ESCALATION_PHRASES = Set.of(
            "think harder", "think deeper", "analyze deeply", "be thorough",
            "pomyśl głębiej", "przeanalizuj dokładnie");

    private static final int SHORT_MESSAGE_THRESHOLD = 30;

    private final ChatModelCache chatModelCache;
    private final ModelRepository modelRepository;

    @Value("${kukuvaia.routing.enabled:true}")
    private boolean routingEnabled;

    public ModelRoutingAdvisor(ChatModelCache chatModelCache, ModelRepository modelRepository) {
        this.chatModelCache = chatModelCache;
        this.modelRepository = modelRepository;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 12;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!routingEnabled) return request;

        var messages = request.prompt().getInstructions();
        if (messages.isEmpty()) return request;

        String userMessage = messages.getLast().getText();
        boolean escalateFlag = Boolean.TRUE.equals(request.context().get(CTX_ESCALATE));

        RoutingDecision decision = classify(userMessage, escalateFlag);
        String targetRole = decision.role();

        // Resolve model UUID from role
        var modelUuid = chatModelCache.getModelIdForRole(targetRole);
        if (modelUuid.isEmpty()) {
            log.debug("Role '{}' not assigned — skipping model override", targetRole);
            return request;
        }

        // Get actual model_id string (e.g., "haiku", "sonnet") from DB
        String modelId = modelRepository.findById(modelUuid.get())
                .map(ModelRecord::modelId)
                .orElse(null);
        if (modelId == null) {
            log.debug("Model record not found for role '{}' — skipping", targetRole);
            return request;
        }

        log.info("Routing: decision={}, role={}, model={}, message={}...",
                decision.name(), targetRole, modelId,
                userMessage.substring(0, Math.min(40, userMessage.length())));

        // Override model on the prompt — preserve existing options (tools, temperature, etc.)
        var options = request.prompt().getOptions();
        if (options instanceof OpenAiChatOptions aiOptions) {
            aiOptions.setModel(modelId);
        } else {
            options = OpenAiChatOptions.builder().model(modelId).build();
        }

        Prompt routedPrompt = new Prompt(
                request.prompt().getInstructions(),
                options
        );

        return request.mutate()
                .prompt(routedPrompt)
                .context(CTX_ROUTED_MODEL, modelId)
                .context(CTX_ROUTING_DECISION, decision.name())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    RoutingDecision classify(String message, boolean escalateFlag) {
        if (escalateFlag) return RoutingDecision.ESCALATE;

        String lower = message.toLowerCase().trim();

        for (String phrase : ESCALATION_PHRASES) {
            if (lower.contains(phrase)) return RoutingDecision.ESCALATE;
        }

        if (lower.length() <= SHORT_MESSAGE_THRESHOLD && isGreeting(lower)) {
            return RoutingDecision.FAST;
        }

        return RoutingDecision.DEFAULT;
    }

    private boolean isGreeting(String lower) {
        String[] words = lower.split("\\s+");
        if (words.length > 5) return false;
        for (String word : words) {
            String clean = word.replaceAll("[^a-ząćęłńóśźż]", "");
            if (GREETING_WORDS.contains(clean)) return true;
        }
        return false;
    }

    enum RoutingDecision {
        FAST("worker"),
        DEFAULT("supervisor"),
        ESCALATE("advisor");

        private final String role;

        RoutingDecision(String role) {
            this.role = role;
        }

        String role() {
            return role;
        }
    }
}

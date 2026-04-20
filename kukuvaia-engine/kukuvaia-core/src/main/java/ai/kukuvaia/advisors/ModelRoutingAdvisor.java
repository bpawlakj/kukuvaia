package ai.kukuvaia.advisors;

import ai.kukuvaia.agent.SessionEscalationService;
import ai.kukuvaia.provider.registry.ChatModelCache;
import ai.kukuvaia.provider.registry.ComplexityMappingService;
import ai.kukuvaia.provider.registry.ModelRecord;
import ai.kukuvaia.provider.registry.ModelRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    // ThreadLocal used by observability (AgentService supervisor span) to read
    // the last routing decision for this turn without plumbing it through the
    // ChatClient return path (which is just a String).
    private static final ThreadLocal<String> LAST_DECISION = new ThreadLocal<>();
    private static final ThreadLocal<String> LAST_MODEL = new ThreadLocal<>();

    /** Returns the most recent routing decision name ("FAST"/"DEFAULT"/"ESCALATE") or null. */
    public static String lastDecision() {
        return LAST_DECISION.get();
    }

    /** Returns the most recent routed model id or null. */
    public static String lastRoutedModel() {
        return LAST_MODEL.get();
    }

    /** Clear ThreadLocals — called by AgentService.runChat in finally. */
    public static void clearLast() {
        LAST_DECISION.remove();
        LAST_MODEL.remove();
    }

    private static final Set<String> GREETING_WORDS = Set.of(
            "hi", "hello", "hey", "cześć", "hej", "siema", "yo", "thanks", "bye", "ok");


    private static final int SHORT_MESSAGE_THRESHOLD = 30;

    private final ChatModelCache chatModelCache;
    private final ModelRepository modelRepository;
    private final TaskClassifier taskClassifier;
    private final ai.kukuvaia.agent.PlanningModeService planningModeService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final ComplexityDetector complexityDetector;
    private final ComplexityMappingService complexityMappingService;
    private final SessionEscalationService escalationService;
    private final MeterRegistry meterRegistry;

    @Value("${kukuvaia.routing.enabled:true}")
    private boolean routingEnabled;

    @Value("${kukuvaia.routing.shadow-mode:false}")
    private boolean shadowMode;

    public ModelRoutingAdvisor(ChatModelCache chatModelCache, ModelRepository modelRepository,
                               TaskClassifier taskClassifier,
                               ai.kukuvaia.agent.PlanningModeService planningModeService,
                               org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                               ComplexityDetector complexityDetector,
                               ComplexityMappingService complexityMappingService,
                               SessionEscalationService escalationService,
                               MeterRegistry meterRegistry) {
        this.chatModelCache = chatModelCache;
        this.modelRepository = modelRepository;
        this.taskClassifier = taskClassifier;
        this.planningModeService = planningModeService;
        this.jdbcTemplate = jdbcTemplate;
        this.complexityDetector = complexityDetector;
        this.complexityMappingService = complexityMappingService;
        this.escalationService = escalationService;
        this.meterRegistry = meterRegistry;
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
        Object sessionIdObj = request.context().get("chat_memory_conversation_id");
        String sessionId = sessionIdObj != null ? sessionIdObj.toString() : null;

        // Combine context flag (from LoopDetectionAdvisor etc.) with the one-shot
        // session flag set by /escalate. Session flag is test-and-remove so it
        // applies to this turn only.
        boolean ctxEscalate = Boolean.TRUE.equals(request.context().get(CTX_ESCALATE));
        boolean sessionEscalate = escalationService.consumeEscalate(sessionId);
        boolean escalateFlag = ctxEscalate || sessionEscalate;

        RoutingDecision decision = classify(userMessage, escalateFlag, sessionId);
        String targetRole = decision.role();

        if (shadowMode) {
            runShadowComparison(userMessage, sessionId, escalateFlag, targetRole);
        }

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

        LAST_DECISION.set(decision.name());
        LAST_MODEL.set(modelId);

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
        return classify(message, escalateFlag, null);
    }

    RoutingDecision classify(String message, boolean escalateFlag, String sessionId) {
        boolean inPlanningMode = sessionId != null && planningModeService.isInPlanningMode(sessionId);
        int priorMessageCount = sessionId != null ? countMessages(sessionId) : 1;

        var result = taskClassifier.classify(message, escalateFlag, inPlanningMode, priorMessageCount);
        log.info("Routing: decision={} reason={} scores={}",
                result.tier(), result.reason(), result.scores());

        return switch (result.tier()) {
            case FAST -> RoutingDecision.FAST;
            case DEFAULT -> RoutingDecision.DEFAULT;
            case ESCALATE -> RoutingDecision.ESCALATE;
        };
    }

    private int countMessages(String sessionId) {
        try {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kukuvaia.spring_ai_chat_memory WHERE conversation_id = ?",
                    Integer.class, sessionId);
            return c != null ? c : 0;
        } catch (Exception e) {
            return 1; // defensive default — not first turn
        }
    }

    /**
     * Shadow-mode — computes the P19 complexity-driven routing decision in parallel
     * with the authoritative keyword classifier and records divergence.
     * Never mutates the request. Errors are swallowed (never block the turn).
     */
    private void runShadowComparison(String userMessage, String sessionId,
                                     boolean escalateFlag, String oldRole) {
        try {
            var result = complexityDetector.detect(userMessage, sessionId, escalateFlag);
            String newRole = complexityMappingService.resolveRole(result.complexity());
            boolean same = newRole.equals(oldRole);
            Counter.builder("kukuvaia.routing.shadow_diff")
                    .tag("old_role", oldRole)
                    .tag("new_role", newRole)
                    .tag("new_complexity", result.complexity().name())
                    .tag("source", result.source().name())
                    .tag("same", Boolean.toString(same))
                    .register(meterRegistry)
                    .increment();
            if (!same) {
                log.info("Routing shadow diff: old_role={} new_role={} complexity={} source={} confidence={}",
                        oldRole, newRole, result.complexity(), result.source(), result.confidence());
            } else {
                log.debug("Routing shadow agree: role={} complexity={} source={}",
                        oldRole, result.complexity(), result.source());
            }
        } catch (Exception e) {
            log.warn("Shadow routing comparison failed: {}", e.getMessage());
        }
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

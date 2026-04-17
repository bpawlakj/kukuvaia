package ai.kukuvaia.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-enforced planning state machine with 3 phases: DISCOVERY → DRAFTING → APPROVAL.
 *
 * Combines two responsibilities:
 * 1. Per-session state management (ConcurrentHashMap, same pattern as PersonaService)
 * 2. BaseAdvisor — injects phase-specific system prompt and filters tools per phase
 *
 * Phase transitions are deterministic (string matching in CommandRouter or tool callback),
 * never LLM-driven.
 */
@Component
public class PlanningModeService implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PlanningModeService.class);
    private static final String CTX_SESSION_ID = "chat_memory_conversation_id";

    private static final Set<String> BLOCKED_IN_DISCOVERY = Set.of("createPlan", "revisePlan", "completeStep");
    private static final Set<String> BLOCKED_IN_DRAFTING = Set.of("completeStep", "updateDiscoveryFacts");
    private static final Set<String> BLOCKED_IN_APPROVAL = Set.of("createPlan", "completeStep", "updateDiscoveryFacts");

    private final ConcurrentHashMap<String, PlanningSession> sessions = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<ChatModel> chatModelProvider;

    public PlanningModeService(JdbcTemplate jdbcTemplate, ObjectProvider<ChatModel> chatModelProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModelProvider = chatModelProvider;
    }

    // --- State management ---

    public void startPlanning(String sessionId, String task) {
        DiscoveryFacts initial = extractFactsOrEmpty(task);
        var session = new PlanningSession(task, PlanningPhase.DISCOVERY, Instant.now(), initial);
        sessions.put(sessionId, session);
        log.info("Planning started: sessionId={}, task='{}', phase=DISCOVERY, extractedFacts={} known/{} excluded/{} gaps/{} ambiguities",
                sessionId, task, initial.knownFacts().size(), initial.excludedOptions().size(),
                initial.remainingGaps().size(), initial.ambiguities().size());
    }

    /**
     * Replace the discovery facts for a session. Called by {@code updateDiscoveryFacts} tool.
     * No-op if session is not in DISCOVERY phase (defensive).
     */
    public DiscoveryFacts updateFacts(String sessionId, DiscoveryFacts newFacts) {
        var updated = sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.phase() != PlanningPhase.DISCOVERY) {
                log.warn("updateFacts called outside DISCOVERY: sessionId={}, phase={}", id, session.phase());
                return session;
            }
            DiscoveryFacts before = session.facts();
            log.info("Discovery facts updated: sessionId={}, known {}→{}, excluded {}→{}, gaps {}→{}, ambiguities {}→{}",
                    id,
                    before.knownFacts().size(), newFacts.knownFacts().size(),
                    before.excludedOptions().size(), newFacts.excludedOptions().size(),
                    before.remainingGaps().size(), newFacts.remainingGaps().size(),
                    before.ambiguities().size(), newFacts.ambiguities().size());
            return session.withFacts(newFacts);
        });
        return updated != null ? updated.facts() : DiscoveryFacts.empty();
    }

    private DiscoveryFacts extractFactsOrEmpty(String task) {
        if (task == null || task.isBlank()) return DiscoveryFacts.empty();
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("ChatModel unavailable, skipping fact extraction");
            return DiscoveryFacts.empty();
        }
        try {
            String prompt = """
                    Extract planning context from this task. Populate all four lists per the schema:
                    - knownFacts: unambiguous facts the user stated.
                    - excludedOptions: options ruled out by the user's wording.
                    - remainingGaps: missing information needed to draft a useful plan.
                    - ambiguities: phrases with multiple plausible meanings in context; each entry is a short clarification question. Do not place ambiguous phrases into knownFacts.

                    Max 7 entries per list. Use the user's language. Empty list if nothing applies.

                    Task: "%s"
                    """.formatted(task);
            DiscoveryFacts facts = ChatClient.create(model).prompt()
                    .user(prompt)
                    .call()
                    .entity(DiscoveryFacts.class);
            return facts != null ? facts : DiscoveryFacts.empty();
        } catch (Exception e) {
            log.warn("Fact extraction failed, starting with empty facts: {}", e.getMessage());
            return DiscoveryFacts.empty();
        }
    }

    public boolean isInPlanningMode(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public Optional<PlanningSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Advance DISCOVERY → DRAFTING. Called by CommandRouter when user signals readiness.
     */
    public void advanceToDrafting(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.phase() == PlanningPhase.DISCOVERY) {
                log.info("Planning phase transition: DISCOVERY → DRAFTING, sessionId={}", id);
                return session.withPhase(PlanningPhase.DRAFTING);
            }
            return session;
        });
    }

    /**
     * Advance DRAFTING → APPROVAL. Called by PlanningTools after successful createPlan.
     */
    public void advanceToApproval(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.phase() == PlanningPhase.DRAFTING) {
                log.info("Planning phase transition: DRAFTING → APPROVAL, sessionId={}", id);
                return session.withPhase(PlanningPhase.APPROVAL);
            }
            return session;
        });
    }

    /**
     * Revert APPROVAL → DRAFTING when user requests changes.
     */
    public void revertToDrafting(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.phase() == PlanningPhase.APPROVAL) {
                log.info("Planning phase transition: APPROVAL → DRAFTING (changes requested), sessionId={}", id);
                return session.withPhase(PlanningPhase.DRAFTING);
            }
            return session;
        });
    }

    /**
     * Approve plan and exit planning mode. Updates plan status in DB to 'active'.
     */
    public void approvePlan(String sessionId) {
        var removed = sessions.remove(sessionId);
        if (removed != null) {
            int updated = jdbcTemplate.update("""
                    UPDATE kukuvaia.plans SET status = 'active', updated_at = NOW()
                    WHERE session_id = ? AND status = 'draft'
                    """, sessionId);
            log.info("Plan approved: sessionId={}, dbUpdated={}", sessionId, updated);
        }
    }

    /**
     * Cancel planning mode. Abandons any draft plan.
     */
    public void cancelPlanning(String sessionId) {
        var removed = sessions.remove(sessionId);
        if (removed != null) {
            jdbcTemplate.update("""
                    UPDATE kukuvaia.plans SET status = 'abandoned', updated_at = NOW()
                    WHERE session_id = ? AND status = 'draft'
                    """, sessionId);
            log.info("Planning cancelled: sessionId={}", sessionId);
        }
    }

    // --- BaseAdvisor implementation ---

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String sessionId = extractSessionId(request);
        if (sessionId == null) return request;

        var session = sessions.get(sessionId);
        if (session == null) return request;

        // 1. Inject phase-specific system prompt
        String phasePrompt = buildPhasePrompt(session);
        var messages = new ArrayList<>(request.prompt().getInstructions());
        messages.addFirst(new SystemMessage(phasePrompt));

        // 2. Filter tools based on phase
        ChatOptions options = request.prompt().getOptions();
        if (options instanceof OpenAiChatOptions aiOptions) {
            Set<String> blocked = blockedTools(session.phase());
            if (!blocked.isEmpty() && aiOptions.getToolCallbacks() != null) {
                var filtered = aiOptions.getToolCallbacks().stream()
                        .filter(cb -> !blocked.contains(cb.getToolDefinition().name()))
                        .toList();
                aiOptions.setToolCallbacks(filtered);
            }
        }

        var enrichedPrompt = new Prompt(messages, options);
        log.debug("Planning advisor: sessionId={}, phase={}, promptInjected={}chars",
                sessionId, session.phase(), phasePrompt.length());

        return request.mutate()
                .prompt(enrichedPrompt)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    // --- Internal ---

    private String extractSessionId(ChatClientRequest request) {
        Object id = request.context().get(CTX_SESSION_ID);
        return id != null ? id.toString() : null;
    }

    private static String formatBullets(List<String> items) {
        if (items == null || items.isEmpty()) return "(none yet)";
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append("\n  - ").append(item);
        }
        return sb.toString();
    }

    private Set<String> blockedTools(PlanningPhase phase) {
        return switch (phase) {
            case DISCOVERY -> BLOCKED_IN_DISCOVERY;
            case DRAFTING -> BLOCKED_IN_DRAFTING;
            case APPROVAL -> BLOCKED_IN_APPROVAL;
        };
    }

    private String buildPhasePrompt(PlanningSession session) {
        return switch (session.phase()) {
            case DISCOVERY -> """
                    ## Planning Mode — Discovery Phase
                    Task: "%s"

                    ### Current state
                    Known: %s
                    Excluded: %s
                    Remaining gaps: %s
                    Ambiguities to resolve: %s

                    ### Rules
                    1. After the user's message, call `updateDiscoveryFacts` with the FULL updated lists if any list changed.
                    2. Ask only about items in "Remaining gaps" and "Ambiguities to resolve". Never ask about items in "Known" or "Excluded".
                    3. Resolve ambiguities first: ask the user to pick an interpretation, then move the resolved item to Known or Excluded.
                    4. When "Remaining gaps" and "Ambiguities to resolve" are both empty, or the user signals readiness, summarize Known facts and ask the user to signal readiness.

                    Do not call createPlan. Respond in the user's language.""".formatted(
                    session.task(),
                    formatBullets(session.facts().knownFacts()),
                    formatBullets(session.facts().excludedOptions()),
                    formatBullets(session.facts().remainingGaps()),
                    formatBullets(session.facts().ambiguities()));
            case DRAFTING -> """
                    ## Planning Mode — Drafting Phase
                    Task: "%s"

                    ### Hard constraints (authoritative — do not violate)
                    Known facts: %s
                    Excluded options: %s

                    ### Rules
                    1. The plan MUST be consistent with Known facts and MUST NOT propose anything from Excluded options or any variant of them.
                    2. Call `createPlan` with the task description and a JSON array of step descriptions. Do not use write_file or other file tools.
                    3. If a step depends on a factual detail you are not certain about (geography, schedules, prices, availability), phrase it as an explicit assumption for the user to verify rather than stating it as fact.
                    4. After saving, present the plan clearly.

                    Respond in the user's language.""".formatted(
                    session.task(),
                    formatBullets(session.facts().knownFacts()),
                    formatBullets(session.facts().excludedOptions()));
            case APPROVAL -> """
                    ## Planning Mode — Approval Phase
                    Task: "%s"

                    ### Hard constraints (authoritative — do not violate)
                    Known facts: %s
                    Excluded options: %s

                    ### Rules
                    1. If the user requests changes, call `revisePlan` with the updated steps and a reason. Revisions MUST still respect Known facts and Excluded options.
                    2. If the user approves, confirm the plan is saved and ready for execution.
                    3. Do not start executing the plan. Wait for the user's decision.

                    Respond in the user's language.""".formatted(
                    session.task(),
                    formatBullets(session.facts().knownFacts()),
                    formatBullets(session.facts().excludedOptions()));
        };
    }
}

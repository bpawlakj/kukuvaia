package ai.kukuvaia.agent;

import ai.kukuvaia.plans.PlansRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private static final Set<String> BLOCKED_IN_APPROVAL = Set.of("createPlan", "revisePlan", "completeStep", "updateDiscoveryFacts");
    /** Executing phase: only {@code completeStep} is unblocked. Structure-changing
     *  tools are blocked so an accidentally-phrased follow-up cannot overwrite an
     *  approved plan — the user must type {@code /plan revise} to transition back. */
    private static final Set<String> BLOCKED_IN_EXECUTING = Set.of("createPlan", "revisePlan", "updateDiscoveryFacts");

    private final ConcurrentHashMap<String, PlanningSession> sessions = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PlansRepository plansRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanningModeService(JdbcTemplate jdbcTemplate,
                               ObjectProvider<ChatModel> chatModelProvider,
                               PlansRepository plansRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModelProvider = chatModelProvider;
        this.plansRepository = plansRepository;
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
            persistFactsForSession(id, newFacts);
            return session.withFacts(newFacts);
        });
        return updated != null ? updated.facts() : DiscoveryFacts.empty();
    }

    private void persistFactsForSession(String sessionId, DiscoveryFacts facts) {
        try {
            String json = objectMapper.writeValueAsString(facts);
            jdbcTemplate.update("""
                    UPDATE kukuvaia.plans SET discovery_facts = ?::jsonb, updated_at = NOW()
                    WHERE session_id = ? AND status = 'draft'
                    """, json, sessionId);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise discovery facts: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to persist facts for session={}: {}", sessionId, e.getMessage());
        }
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

    /**
     * Returns true if there is a draft plan in the DB for this session.
     * Used by {@code CommandRouter} as a safety net when the in-memory session
     * map has evicted the session (engine restart, explicit cancel, etc.) but
     * the user is still interacting with a pending approval decision.
     */
    public boolean hasDraftPlan(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kukuvaia.plans WHERE session_id = ? AND status = 'draft'",
                Integer.class, sessionId);
        return count != null && count > 0;
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
                persistPhaseForSession(id, "drafting");
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
                persistPhaseForSession(id, "approval");
                return session.withPhase(PlanningPhase.APPROVAL);
            }
            return session;
        });
    }

    /**
     * Persist the phase column on the active draft plan row for this session.
     * Best-effort: write fails are logged and swallowed so they never block a phase transition.
     */
    private void persistPhaseForSession(String sessionId, String phase) {
        try {
            jdbcTemplate.update("""
                    UPDATE kukuvaia.plans SET phase = ?, updated_at = NOW()
                    WHERE session_id = ? AND status = 'draft'
                    """, phase, sessionId);
        } catch (Exception e) {
            log.warn("Failed to persist phase={} for session={}: {}", phase, sessionId, e.getMessage());
        }
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
     * Approve plan and transition to EXECUTING. Updates plan status in DB to
     * 'active' and phase to 'executing'. Unlike the previous behaviour this
     * does NOT remove the in-memory session — the user stays in planning mode
     * so step completions and progress reports can be handled naturally.
     * Exit happens through {@code /plan done}, {@code /plan exit}, or
     * {@code /plan cancel}.
     */
    public void approvePlan(String sessionId) {
        int updated = jdbcTemplate.update("""
                UPDATE kukuvaia.plans SET status = 'active', phase = 'executing', updated_at = NOW()
                WHERE session_id = ? AND status = 'draft'
                """, sessionId);
        if (updated > 0) {
            sessions.computeIfPresent(sessionId, (id, session) -> session.withPhase(PlanningPhase.EXECUTING));
            log.info("Plan approved → EXECUTING: sessionId={}, dbUpdated={}", sessionId, updated);
        } else {
            log.warn("approvePlan: no draft plan found for sessionId={} — " +
                    "either the plan was never saved or it was already approved/abandoned",
                    sessionId);
        }
    }

    /**
     * Drop from EXECUTING back to DRAFTING so the user can restructure the
     * plan. DB status stays 'active' until the revised plan is re-approved —
     * that way a draft-in-progress does not wipe the currently-live plan
     * mid-edit. Phase is persisted so the picker reflects the true state.
     */
    public void revertExecutingToDrafting(String sessionId) {
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.phase() == PlanningPhase.EXECUTING) {
                log.info("Planning transition: EXECUTING → DRAFTING (/plan revise), sessionId={}", id);
                persistPhaseForSession(id, "drafting");
                return session.withPhase(PlanningPhase.DRAFTING);
            }
            return session;
        });
    }

    /**
     * Close the plan as completed. Sets status='completed' + phase='done' in
     * DB and removes the in-memory session. Per decision B2: no validation
     * that all steps are checked — the user may close with partial progress.
     *
     * @return true if a row was updated, false when no active plan exists.
     */
    public boolean markExecutingDone(String sessionId) {
        int updated = jdbcTemplate.update("""
                UPDATE kukuvaia.plans SET status = 'completed', phase = 'done', updated_at = NOW()
                WHERE session_id = ? AND status = 'active'
                """, sessionId);
        if (updated > 0) {
            sessions.remove(sessionId);
            log.info("Plan marked done: sessionId={}", sessionId);
            return true;
        }
        log.warn("markExecutingDone: no active plan found for sessionId={}", sessionId);
        return false;
    }

    /**
     * Leave plan mode without changing status or phase in DB. Used by
     * {@code /plan exit} so the user can chat normally; the plan remains
     * 'active' / 'executing' and can be resumed later via
     * {@code /plan resume <id>}.
     */
    public void exitPlanMode(String sessionId) {
        var removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("Exited plan mode (in-memory only, DB unchanged): sessionId={}", sessionId);
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

    // --- P21 resume / combine / abandon ---

    /**
     * Rehydrate a planning session from a DB plan row. Loads phase + facts into
     * the in-memory map so the advisor chain picks up the same DISCOVERY / DRAFTING /
     * APPROVAL behaviour as if the user had never left.
     *
     * Cross-session resume is allowed: the plan becomes attached to {@code sessionId}
     * even if it was originally created in a different session. DB's session_id stays
     * on the row as audit trail; the in-memory map is keyed by the current session.
     *
     * @throws IllegalStateException when the plan does not exist, belongs to another
     *         user (handled with same message to avoid leaking existence), or is in a
     *         terminal phase (done/abandoned).
     */
    public PlanningSession resumeFromDb(UUID planId, String sessionId) {
        // TODO: user id should come from auth context — using bartek fallback to match rest of codebase.
        String userId = "bartek";
        var state = plansRepository.findFullState(planId, userId)
                .orElseThrow(() -> new IllegalStateException("Plan not found: " + planId));

        if ("abandoned".equals(state.phase()) || "done".equals(state.phase())) {
            throw new IllegalStateException("Plan is " + state.phase() + " — cannot resume");
        }

        DiscoveryFacts facts = parseFacts(state.discoveryFactsJson());
        PlanningPhase phase = mapPhase(state.phase());
        var session = new PlanningSession(state.task(), phase, Instant.now(), facts);
        sessions.put(sessionId, session);

        // Re-point the plan row at the current session (cross-session resume).
        // Original session_id is preserved in log for audit.
        jdbcTemplate.update("""
                UPDATE kukuvaia.plans SET session_id = ?, updated_at = NOW() WHERE id = ?
                """, sessionId, planId);
        log.info("Plan resumed: planId={} originalSession={} currentSession={} phase={}",
                planId, state.sessionId(), sessionId, phase);
        return session;
    }

    /**
     * Create a new plan that merges facts from multiple parent plans, starts in
     * DISCOVERY phase, and inserts {@code plan_links} rows (relation='combines').
     * Returns the new plan's id + merged facts for the caller to acknowledge.
     */
    public CombineResult combinePlans(List<UUID> parentIds, String newTask, String sessionId) {
        if (parentIds == null || parentIds.isEmpty()) {
            throw new IllegalStateException("combinePlans requires at least one parent");
        }
        // TODO: user id from auth context.
        String userId = "bartek";
        if (!plansRepository.allBelongToUser(parentIds, userId)) {
            throw new IllegalStateException("One or more parent plans do not belong to current user");
        }

        // Load parent facts, merge, persist.
        DiscoveryFacts merged = DiscoveryFacts.empty();
        for (UUID parent : parentIds) {
            var parentState = plansRepository.findFullState(parent, userId).orElse(null);
            if (parentState == null) continue;
            DiscoveryFacts parentFacts = parseFacts(parentState.discoveryFactsJson());
            merged = mergeFacts(merged, parentFacts);
        }

        String factsJson;
        try {
            factsJson = objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            factsJson = "{}";
        }

        String derivedName = newTask.length() > 60 ? newTask.substring(0, 57) + "…" : newTask;
        UUID newPlanId = plansRepository.createPending(
                sessionId, userId, newTask, derivedName, "discovery", factsJson);

        for (UUID parent : parentIds) {
            plansRepository.createLink(newPlanId, parent, "combines", null);
        }

        // Seed in-memory session with the merged state. Caller (CommandRouter)
        // typically forwards to agentService right after this.
        var session = new PlanningSession(newTask, PlanningPhase.DISCOVERY, Instant.now(), merged);
        sessions.put(sessionId, session);

        log.info("Plans combined: newPlanId={} parents={} mergedKnown={} mergedExcluded={}",
                newPlanId, parentIds, merged.knownFacts().size(), merged.excludedOptions().size());
        return new CombineResult(newPlanId, merged);
    }

    /**
     * Mark a plan as abandoned — destructive, DB-level operation used by the
     * picker's two-step 'd' action. Returns the number of rows affected.
     */
    public int abandonPlan(UUID planId) {
        int updated = jdbcTemplate.update("""
                UPDATE kukuvaia.plans SET status = 'abandoned', phase = 'abandoned', updated_at = NOW()
                WHERE id = ? AND status <> 'abandoned'
                """, planId);
        if (updated > 0) {
            log.info("Plan abandoned: planId={}", planId);
        }
        return updated;
    }

    /**
     * Naive merge — union with exact-string dedup. Good enough for MVP; upgrade
     * to LLM-assisted merge when users report duplication pain (see P21 open Q#1).
     */
    private static DiscoveryFacts mergeFacts(DiscoveryFacts a, DiscoveryFacts b) {
        return new DiscoveryFacts(
                dedupeUnion(a.knownFacts(), b.knownFacts()),
                dedupeUnion(a.excludedOptions(), b.excludedOptions()),
                dedupeUnion(a.remainingGaps(), b.remainingGaps()),
                List.of()   // ambiguities are always re-asked during new DISCOVERY
        );
    }

    private static List<String> dedupeUnion(List<String> first, List<String> second) {
        Set<String> seen = new LinkedHashSet<>();
        if (first != null) seen.addAll(first);
        if (second != null) seen.addAll(second);
        return List.copyOf(seen);
    }

    private DiscoveryFacts parseFacts(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return DiscoveryFacts.empty();
        }
        try {
            return objectMapper.readValue(json, DiscoveryFacts.class);
        } catch (Exception e) {
            log.warn("Failed to parse discovery_facts JSON, treating as empty: {}", e.getMessage());
            return DiscoveryFacts.empty();
        }
    }

    private static PlanningPhase mapPhase(String dbPhase) {
        if (dbPhase == null) return PlanningPhase.APPROVAL;
        return switch (dbPhase) {
            case "discovery" -> PlanningPhase.DISCOVERY;
            case "drafting" -> PlanningPhase.DRAFTING;
            case "approval" -> PlanningPhase.APPROVAL;
            case "executing" -> PlanningPhase.EXECUTING;
            default -> PlanningPhase.APPROVAL;
        };
    }

    /** Returned from {@link #combinePlans} so the caller can drive downstream UX. */
    public record CombineResult(UUID newPlanId, DiscoveryFacts mergedFacts) {}

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
        String phasePrompt = buildPhasePrompt(session, sessionId);
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
            case EXECUTING -> BLOCKED_IN_EXECUTING;
        };
    }

    private String buildPhasePrompt(PlanningSession session, String sessionId) {
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

                    ### Current state
                    Known facts: %s
                    Excluded options: %s
                    Open assumptions to resolve: %s

                    ### Rules
                    1. The plan MUST be consistent with Known facts and MUST NOT propose anything from Excluded options or any variant of them.
                    2. Treat each entry in "Open assumptions to resolve" as your own decision: pick the most reasonable default for the domain and inline it in the step it affects, prefixed with `Assumption:`. Do NOT re-ask the user; do NOT emit a step that merely restates the gap as a question.
                    3. Every step MUST be self-contained and actionable: expand it with sub-items that name specific entities, quantitative ranges where relevant, decision branches, and prerequisites. A single-line headline is not a step.
                    4. When a factual detail is uncertain, commit to a concrete value or range and label it with `Assumption:`. Avoid vague qualifiers as substitutes for numbers.
                    5. Call `createPlan` with the task description and a JSON array of step descriptions; each step description is multi-line markdown — bullets and assumptions allowed inline. Do not use write_file or other file tools.
                    6. After saving, present the plan clearly.

                    Respond in the user's language.""".formatted(
                    session.task(),
                    formatBullets(session.facts().knownFacts()),
                    formatBullets(session.facts().excludedOptions()),
                    formatBullets(session.facts().remainingGaps()));
            case APPROVAL -> {
                String planStatus = resolvePlanStatus(sessionId);
                yield """
                    ## Planning Mode — Approval Phase
                    Task: "%s"

                    ### Current plan state (authoritative — read from DB, not inferred)
                    Plan status: %s
                    Known facts: %s
                    Excluded options: %s

                    ### Rules
                    1. The Plan status line above is the SOURCE OF TRUTH. It reads straight from the database. Do NOT claim the plan is approved/active/saved unless it says so.
                    2. Never self-declare approval. Only the user can approve — the system sets status to 'active' AFTER the user says a confirmation word.
                    3. If the user requests changes, call `revisePlan` with updated steps and reason. Revisions MUST respect Known facts and Excluded options.
                    4. If the plan is still 'draft', ask the user a clear yes/no question such as: "Is everything clear — shall I approve the plan?" Then WAIT for their explicit confirmation word. Phrase the question in the user's language.
                    5. If the plan is already 'active', confirm it is saved and summarise next steps. Never re-ask for approval.
                    6. Do not start executing the plan. Wait for the user's explicit confirmation (e.g. "yes", "ok", "approve", "confirm" — accept semantically equivalent confirmations in the user's own language).

                    Respond in the user's language.""".formatted(
                    session.task(),
                    planStatus,
                    formatBullets(session.facts().knownFacts()),
                    formatBullets(session.facts().excludedOptions()));
            }
            case EXECUTING -> {
                String planStatus = resolvePlanStatus(sessionId);
                String stepsSummary = resolveStepsSummary(sessionId);
                yield """
                    ## Planning Mode — Executing Phase
                    Plan: "%s"

                    ### Current plan state (authoritative — read from DB, not inferred)
                    Plan status: %s
                    Steps: %s

                    ### Rules
                    1. The plan is approved and active. Do NOT propose new steps, rewrites, or restructuring.
                    2. When the user reports finishing a step, call `completeStep(stepIndex, result)` with the 0-based step index and a short result summary.
                    3. You MAY discuss, explain, or advise — reference the plan naturally.
                    4. If the user wants structural changes, respond: "To change the plan structure, please type /plan revise."
                    5. If the user signals the whole plan is done, respond: "To close the plan, please type /plan done."
                    6. If the user wants to leave plan mode without closing, respond: "To leave plan mode without changing the plan, please type /plan exit."
                    7. Never call `createPlan`, `revisePlan`, or `updateDiscoveryFacts` — they are disabled in this phase.

                    Respond in the user's language.""".formatted(
                    session.task(),
                    planStatus,
                    stepsSummary);
            }
        };
    }

    /**
     * Render the current {@code steps} JSONB column as a short human-readable
     * summary for the EXECUTING prompt. Best-effort — a parse failure collapses
     * to "(unavailable)" rather than blocking the chat turn.
     */
    private String resolveStepsSummary(String sessionId) {
        try {
            String stepsJson = jdbcTemplate.queryForObject(
                    "SELECT steps::text FROM kukuvaia.plans " +
                            "WHERE session_id = ? AND status = 'active' " +
                            "ORDER BY updated_at DESC LIMIT 1",
                    String.class, sessionId);
            if (stepsJson == null || stepsJson.isBlank()) return "(no steps)";
            var node = objectMapper.readTree(stepsJson);
            if (!node.isArray() || node.isEmpty()) return "(no steps)";
            var sb = new StringBuilder();
            for (int i = 0; i < node.size(); i++) {
                var step = node.get(i);
                String description = step.has("description") ? step.get("description").asText() : step.asText();
                String status = step.has("status") ? step.get("status").asText() : "pending";
                sb.append("\n  ").append(i).append(". [").append(status).append("] ").append(truncate(description, 120));
            }
            return sb.toString();
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return "(no active plan)";
        } catch (Exception e) {
            log.debug("resolveStepsSummary failed for sessionId={}: {}", sessionId, e.getMessage());
            return "(unavailable)";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Reads the most recent plan status for this session from the DB. Used to
     * ground the LLM's APPROVAL prompt so it never claims a plan is approved
     * when the DB still has it as draft.
     */
    private String resolvePlanStatus(String sessionId) {
        try {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM kukuvaia.plans WHERE session_id = ? " +
                            "ORDER BY created_at DESC LIMIT 1",
                    String.class, sessionId);
            return status != null ? status : "(no plan yet)";
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return "(no plan yet)";
        } catch (Exception e) {
            log.debug("resolvePlanStatus failed for sessionId={}: {}", sessionId, e.getMessage());
            return "(unknown)";
        }
    }
}

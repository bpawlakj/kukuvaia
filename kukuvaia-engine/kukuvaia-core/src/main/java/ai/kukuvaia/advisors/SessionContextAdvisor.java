package ai.kukuvaia.advisors;

import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.memory.model.Commitment;
import ai.kukuvaia.memory.repository.CommitmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Injects DB-derived session facts into the system prompt so the LLM can offer
 * grounded recommendations at the start of a session or when the user's intent
 * is ambiguous. Deliberate-by-design: the advisor does not tell the LLM HOW
 * to behave (that belongs to the persona prompt) — it only surfaces FACTS.
 *
 * Skipped when the session is already in planning mode — {@code PlanningModeService}
 * owns the context for that flow.
 *
 * Facts surfaced:
 *  - is this a new session (no prior messages) or a returning one (with count)
 *  - any draft or active plans attached to the session
 *
 * Runs after {@code PlanningModeService} so planning-phase prompts take priority
 * when the user is mid-flow on a specific plan.
 */
@Component
public class SessionContextAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SessionContextAdvisor.class);
    private static final String CTX_SESSION_ID = "chat_memory_conversation_id";
    private static final String CTX_USER_ID = "kukuvaia.userId";

    private final JdbcTemplate jdbcTemplate;
    private final PlanningModeService planningModeService;
    private final CommitmentRepository commitmentRepository;

    public SessionContextAdvisor(JdbcTemplate jdbcTemplate,
                                 PlanningModeService planningModeService,
                                 CommitmentRepository commitmentRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.planningModeService = planningModeService;
        this.commitmentRepository = commitmentRepository;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Object idObj = request.context().get(CTX_SESSION_ID);
        if (idObj == null) {
            log.debug("SessionContextAdvisor: no sessionId in context, skipping");
            return request;
        }
        String sessionId = idObj.toString();

        // Skip on FAST-tier routing (greetings, tiny prompts → small worker model).
        // Worker models (qwen, nova-micro) cannot digest the full supervisor context
        // without returning empty responses. The user's "hej" does not need plans,
        // commitments, or memories — it needs a simple reply.
        String routingDecision = ModelRoutingAdvisor.lastDecision();
        if ("FAST".equals(routingDecision)) {
            log.debug("SessionContextAdvisor: FAST routing active, skipping context injection");
            return request;
        }

        // Planning mode owns its context — do not duplicate.
        if (planningModeService.isInPlanningMode(sessionId)) {
            log.debug("SessionContextAdvisor: session {} is in planning mode, skipping", sessionId);
            return request;
        }

        String userId = null;
        Object userObj = request.context().get(CTX_USER_ID);
        if (userObj != null) {
            userId = userObj.toString();
        }

        String block = buildContextBlock(sessionId, userId);
        if (block.isBlank()) {
            log.debug("SessionContextAdvisor: empty context block, skipping");
            return request;
        }

        log.info("SessionContextAdvisor injected for session={} user={} ({} chars)",
                sessionId, userId, block.length());
        var messages = new ArrayList<>(request.prompt().getInstructions());
        messages.addFirst(new SystemMessage(block));
        var newPrompt = new Prompt(messages, request.prompt().getOptions());
        return request.mutate().prompt(newPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private String buildContextBlock(String sessionId, String userId) {
        try {
            int messageCount = countMessages(sessionId);
            List<Map<String, Object>> sessionPlans = findLivePlans(sessionId);
            List<Map<String, Object>> otherUserPlans = userId != null
                    ? findLivePlansForUserExcluding(userId, sessionId)
                    : List.of();
            List<Commitment> openCommitments = userId != null
                    ? commitmentRepository.findOpenByUser(userId, 5)
                    : List.of();
            log.info("SessionContextAdvisor.buildContextBlock: session={} user={} msgCount={} " +
                            "sessionPlans={} otherUserPlans={} commitments={}",
                    sessionId, userId, messageCount, sessionPlans.size(),
                    otherUserPlans.size(), openCommitments.size());

            boolean hasUnfinished = !sessionPlans.isEmpty()
                    || !otherUserPlans.isEmpty()
                    || !openCommitments.isEmpty();

            var sb = new StringBuilder();
            sb.append("## Session Context — READ AND OBEY\n\n");

            // Directive FIRST so small models attend to it even when the rest of
            // the prompt is long. Hard-contract wording: "MUST" + explicit
            // failure mode ("do not answer 'no unfinished work' if list below
            // is non-empty"). Re-ordered from the previous version where the
            // rule sat at the bottom and mistral-small ignored it.
            sb.append("### HARD CONTRACT (authoritative, non-negotiable)\n");
            if (hasUnfinished) {
                sb.append("⚠️ The user has UNFINISHED WORK listed below.\n")
                        .append("1. You MUST acknowledge each listed item in your FIRST response.\n")
                        .append("2. You MUST NOT answer with phrases like 'no unfinished plans', ")
                        .append("'no active projects', or 'nothing in progress' — that contradicts the facts below.\n")
                        .append("3. Ask the user what to do with EACH item: continue, approve, revise, mark done, archive, or drop.\n");
            } else {
                sb.append("No unfinished work on record. You MAY greet the user briefly and ask how you can help.\n");
            }
            sb.append("\n");

            sb.append("### Facts\n");
            if (messageCount == 0) {
                sb.append("- Session: NEW (user has not written anything yet or this is their first turn).\n");
            } else {
                sb.append("- Session: ONGOING (").append(messageCount).append(" prior messages).\n");
            }

            if (!sessionPlans.isEmpty()) {
                sb.append("- Plans in THIS session:\n");
                for (var plan : sessionPlans) {
                    sb.append("  - ")
                            .append(plan.get("task"))
                            .append("  (status: ").append(plan.get("status")).append(")\n");
                }
            }

            if (!otherUserPlans.isEmpty()) {
                sb.append("- Plans for this user in OTHER sessions:\n");
                for (var plan : otherUserPlans) {
                    sb.append("  - ")
                            .append(plan.get("task"))
                            .append("  (status: ").append(plan.get("status"))
                            .append(", session: ").append(plan.get("session_id")).append(")\n");
                }
            }

            if (!openCommitments.isEmpty()) {
                sb.append("- Open commitments:\n");
                for (Commitment c : openCommitments) {
                    sb.append("  - ")
                            .append(c.summary())
                            .append("  (id: ").append(c.id().toString(), 0, 8)
                            .append(", status: ").append(c.status());
                    if (c.dueHint() != null && !c.dueHint().isBlank()) {
                        sb.append(", due: ").append(c.dueHint());
                    }
                    sb.append(")\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("SessionContextAdvisor failed for sessionId={}: {}", sessionId, e.getMessage());
            return "";
        }
    }

    private int countMessages(String sessionId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kukuvaia.spring_ai_chat_memory WHERE conversation_id = ?",
                    Integer.class, sessionId);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Map<String, Object>> findLivePlans(String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT task, status FROM kukuvaia.plans " +
                        "WHERE session_id = ? AND status IN ('draft', 'active') " +
                        "ORDER BY created_at DESC LIMIT 3",
                sessionId);
    }

    /**
     * Returns draft/active plans for this user from OTHER sessions. Limit kept
     * small (5) to avoid bloating the system prompt — most users won't have
     * that many loose drafts.
     */
    private List<Map<String, Object>> findLivePlansForUserExcluding(String userId, String excludedSessionId) {
        return jdbcTemplate.queryForList(
                "SELECT task, status, session_id FROM kukuvaia.plans " +
                        "WHERE user_id = ? AND session_id <> ? AND status IN ('draft', 'active') " +
                        "ORDER BY created_at DESC LIMIT 5",
                userId, excludedSessionId);
    }
}

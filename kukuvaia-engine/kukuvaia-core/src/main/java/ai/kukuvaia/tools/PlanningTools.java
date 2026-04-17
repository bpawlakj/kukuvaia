package ai.kukuvaia.tools;

import ai.kukuvaia.agent.DiscoveryFacts;
import ai.kukuvaia.agent.PlanningModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Planning tools for step-by-step task execution.
 * Plans persist per session in the {@code kukuvaia.plans} table.
 * Session and user context are set via ThreadLocal before each ChatClient call.
 */
@Component
public class PlanningTools {

    private static final Logger log = LoggerFactory.getLogger(PlanningTools.class);
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private final JdbcTemplate jdbcTemplate;
    private final PlanningModeService planningModeService;

    public PlanningTools(JdbcTemplate jdbcTemplate, PlanningModeService planningModeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.planningModeService = planningModeService;
    }

    /** Set context before ChatClient call. Called by AgentService. */
    public static void setContext(String sessionId, String userId) {
        SESSION_ID.set(sessionId);
        USER_ID.set(userId);
    }

    /** Clear context after ChatClient call. */
    public static void clearContext() {
        SESSION_ID.remove();
        USER_ID.remove();
    }

    private String currentSessionId() { return SESSION_ID.get() != null ? SESSION_ID.get() : "unknown"; }
    private String currentUserId() { return USER_ID.get() != null ? USER_ID.get() : "unknown"; }

    @Tool(description = "Create a step-by-step plan for a complex task. Session and user are resolved automatically — only provide task and steps.")
    public Map<String, Object> createPlan(
            @ToolParam(description = "Task description") String task,
            @ToolParam(description = "JSON array of step descriptions, e.g. [\"Step 1\",\"Step 2\"]") String stepsJson) {
        String sessionId = currentSessionId();
        String userId = currentUserId();
        log.info("createPlan: sessionId={}, userId={}, task={}", sessionId, userId, task);

        // Abandon any existing draft/active plan for this session
        jdbcTemplate.update("""
                UPDATE kukuvaia.plans SET status = 'abandoned', updated_at = NOW()
                WHERE session_id = ? AND status IN ('draft', 'active')
                """, sessionId);

        // Insert as 'draft' — becomes 'active' only after user approval
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.plans (session_id, user_id, task, steps, status)
                VALUES (?, ?, ?, ?::jsonb, 'draft')
                """, sessionId, userId, task, stepsJson);

        // Notify planning state machine: DRAFTING → APPROVAL
        planningModeService.advanceToApproval(sessionId);

        return Map.of("created", true, "task", task);
    }

    @Tool(description = "Mark a plan step as completed with a result summary. Session is resolved automatically.")
    public Map<String, Object> completeStep(
            @ToolParam(description = "Step index (0-based)") int stepIndex,
            @ToolParam(description = "Result summary for this step") String result) {
        String sessionId = currentSessionId();
        log.info("completeStep: sessionId={}, step={}", sessionId, stepIndex);
        jdbcTemplate.update("""
                UPDATE kukuvaia.plans
                SET steps = jsonb_set(steps, ?::text[], ?::jsonb), updated_at = NOW()
                WHERE session_id = ? AND status = 'active'
                """,
                "{" + stepIndex + ",status}", "\"completed\"", sessionId);
        return Map.of("completed", true, "stepIndex", stepIndex);
    }

    @Tool(description = """
            Update the planning discovery fact list with the FULL current state (not a delta). \
            Call during the discovery phase whenever the user's latest message changes any list.""")
    public Map<String, Object> updateDiscoveryFacts(
            @ToolParam(description = "Unambiguous facts the user has stated or confirmed") List<String> knownFacts,
            @ToolParam(description = "Options the user has ruled out") List<String> excludedOptions,
            @ToolParam(description = "Questions still needed to build a plan") List<String> remainingGaps,
            @ToolParam(description = "Phrases with multiple plausible meanings awaiting user clarification") List<String> ambiguities) {
        String sessionId = currentSessionId();
        var applied = planningModeService.updateFacts(sessionId,
                new DiscoveryFacts(knownFacts, excludedOptions, remainingGaps, ambiguities));
        log.info("updateDiscoveryFacts: sessionId={}, known={}, excluded={}, gaps={}, ambiguities={}",
                sessionId, applied.knownFacts().size(), applied.excludedOptions().size(),
                applied.remainingGaps().size(), applied.ambiguities().size());
        return Map.of(
                "known", applied.knownFacts(),
                "excluded", applied.excludedOptions(),
                "gaps", applied.remainingGaps(),
                "ambiguities", applied.ambiguities()
        );
    }

    @Tool(description = "Revise the current plan with updated steps. Session is resolved automatically.")
    public Map<String, Object> revisePlan(
            @ToolParam(description = "Updated JSON array of step descriptions") String stepsJson,
            @ToolParam(description = "Reason for revision") String reason) {
        String sessionId = currentSessionId();
        log.info("revisePlan: sessionId={}, reason={}", sessionId, reason);
        jdbcTemplate.update("""
                UPDATE kukuvaia.plans
                SET steps = ?::jsonb, updated_at = NOW()
                WHERE session_id = ? AND status IN ('draft', 'active')
                """, stepsJson, sessionId);
        return Map.of("revised", true, "reason", reason);
    }
}

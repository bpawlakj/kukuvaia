package ai.kukuvaia.tools;

import ai.kukuvaia.agent.DiscoveryFacts;
import ai.kukuvaia.agent.PlanningModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    /** Public thread-local accessor for observability hooks that run inside the advisor chain. */
    public static String getCurrentSessionId() {
        return SESSION_ID.get();
    }

    private String currentSessionId() { return SESSION_ID.get() != null ? SESSION_ID.get() : "unknown"; }
    private String currentUserId() { return USER_ID.get() != null ? USER_ID.get() : "unknown"; }

    @Tool(description = """
            Enter planning mode for a given task. Call this WHENEVER the user expresses an intent to \
            plan, structure, or design something — including when their message contains '/plan' \
            anywhere in the text (not only at the start), or phrases like "let's plan X", "plan for X", \
            "create a plan for X", "help me plan X". Apply the same rule when the user expresses this \
            intent in a non-English language; recognise the intent semantically, not by keyword. \
            Starts the DISCOVERY phase: the system will proactively gather requirements before drafting. \
            Safe to call even if already in planning mode — it replaces the current planning session. \
            Always call this BEFORE replying, so the CLI's planning toolbar appears for the user.""")
    public Map<String, Object> startPlanning(
            @ToolParam(description = "Short task description summarising what the user wants to plan. "
                    + "Keep it in the user's original language so downstream rendering matches.")
            String task) {
        String sessionId = currentSessionId();
        if (task == null || task.isBlank()) {
            return Map.of("ok", false, "error", "task must not be blank");
        }
        planningModeService.startPlanning(sessionId, task);
        log.info("startPlanning: sessionId={} task='{}'", sessionId, task);
        return Map.of("ok", true, "phase", "DISCOVERY", "task", task);
    }

    @Tool(description = "Create a step-by-step plan for a complex task. Session and user are resolved "
            + "automatically — only provide task and steps. BOTH parameters are required: never call "
            + "this with task=null or empty steps. If you don't yet have a task summary or step list, "
            + "ask the user rather than calling the tool.")
    @Transactional
    public Map<String, Object> createPlan(
            @ToolParam(description = "Task description — MUST be a non-empty sentence summarising what the plan covers.")
            String task,
            @ToolParam(description = "JSON array of step descriptions, e.g. [\"Step 1\",\"Step 2\"]. MUST be a valid non-empty JSON array.")
            String stepsJson) {
        String sessionId = currentSessionId();
        String userId = currentUserId();

        // Fail-fast validation — reject bad tool calls BEFORE any DB write. Without this,
        // a malformed call (e.g. a thinking-model that returns reasoning-only with null args)
        // would silently abandon the user's in-flight approved plans via the UPDATE below
        // and then fail on the NOT-NULL INSERT, leaving nothing to replace them.
        String cleanTask = task == null ? null : task.trim();
        String cleanSteps = stepsJson == null ? null : stepsJson.trim();
        if (cleanTask == null || cleanTask.isEmpty()) {
            log.warn("createPlan rejected: blank task — sessionId={}", sessionId);
            return Map.of("created", false, "error",
                    "task is required and must be a non-empty string. Ask the user for a task summary before calling createPlan.");
        }
        if (cleanSteps == null || cleanSteps.isEmpty() || !cleanSteps.startsWith("[") || !cleanSteps.endsWith("]")) {
            log.warn("createPlan rejected: invalid stepsJson — sessionId={} starts={} ends={}",
                    sessionId,
                    cleanSteps == null ? "null" : cleanSteps.isEmpty() ? "empty" : cleanSteps.substring(0, Math.min(1, cleanSteps.length())),
                    cleanSteps == null || cleanSteps.isEmpty() ? "null" : cleanSteps.substring(Math.max(0, cleanSteps.length() - 1)));
            return Map.of("created", false, "error",
                    "stepsJson must be a JSON array like [\"Step 1\",\"Step 2\"]. Cannot be null or empty.");
        }

        log.info("createPlan: sessionId={}, userId={}, task={}", sessionId, userId, cleanTask);

        // Abandon only existing DRAFTS for this session — drafts are pre-approval, so
        // replacing them with a new draft is the intent. We must NOT touch 'active'
        // (approved-and-executing) or 'completed' plans — those belong to earlier
        // planning sessions within the same chat and the user still needs them.
        jdbcTemplate.update("""
                UPDATE kukuvaia.plans SET status = 'abandoned', updated_at = NOW()
                WHERE session_id = ? AND status = 'draft'
                """, sessionId);

        // Insert as 'draft' — becomes 'active' only after user approval
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.plans (session_id, user_id, task, steps, status)
                VALUES (?, ?, ?, ?::jsonb, 'draft')
                """, sessionId, userId, cleanTask, cleanSteps);

        // Notify planning state machine: DRAFTING → APPROVAL
        planningModeService.advanceToApproval(sessionId);

        return Map.of("created", true, "task", cleanTask);
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

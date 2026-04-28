package ai.kukuvaia.tools;

import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.plans.PlanEntry;
import ai.kukuvaia.plans.PlansRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P21 Phase D — natural-language entry points for the plan registry.
 *
 * The agent calls these tools when the user asks about their plans or wants
 * to combine / resume one. {@code list_plans} returns structured rows so the
 * LLM can summarise them as plain text in its reply. The interactive picker
 * is ONLY opened via the {@code /plans} slash command or when the user
 * explicitly asks to resume a specific plan — natural-language queries get
 * a text summary, not a picker overlay.
 *
 * Hallucination guard: statuses are delivered both verbatim ({@code status},
 * {@code phase}) and as pre-computed human labels ({@code statusLabel}) so the
 * model never has to invent a translation. An earlier bug had every plan
 * shown with the same label regardless of its real status.
 *
 * Session + user context is shared with {@link PlanningTools} — same ThreadLocal.
 */
@Component
public class PlanRegistryTools {

    private static final Logger log = LoggerFactory.getLogger(PlanRegistryTools.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final PlansRepository plansRepository;
    private final PlanningModeService planningModeService;

    public PlanRegistryTools(PlansRepository plansRepository,
                             PlanningModeService planningModeService) {
        this.plansRepository = plansRepository;
        this.planningModeService = planningModeService;
    }

    @Tool(description = """
            List the user's plans as structured data. Status filter: \
            'draft' | 'active' | 'completed' | 'abandoned' | null for all. \
            Returns a JSON array with per-plan {id, name, task, status, phase, statusLabel, updatedAt}. \

            How to reply: summarise the returned plans as a short text or markdown list. \
            Use the `statusLabel` field VERBATIM — never translate or relabel it yourself, and \
            never apply the label of one row to a different row (each row has its own status). \
            Do NOT open the interactive picker for natural-language queries — the user gets \
            the picker only via the `/plans` slash command.""")
    public Map<String, Object> list_plans(
            @ToolParam(description = "Optional status filter (draft/active/completed/abandoned). Null returns all.",
                    required = false) String status,
            @ToolParam(description = "Max plans returned. Defaults to 20, capped at 100.",
                    required = false) Integer limit) {
        String userId = currentUserId();
        int safeLimit = clampLimit(limit);
        String normalisedStatus = normaliseStatus(status);
        List<PlanEntry> plans = plansRepository.findByUser(userId, normalisedStatus, safeLimit);

        List<Map<String, Object>> rows = new ArrayList<>(plans.size());
        for (PlanEntry p : plans) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id().toString());
            row.put("name", p.name() != null ? p.name() : p.taskPreview());
            row.put("task", p.taskPreview());
            row.put("status", p.status());
            row.put("phase", p.phase());
            row.put("statusLabel", statusLabel(p.status(), p.phase()));
            row.put("updatedAt", p.updatedAt() != null ? p.updatedAt().toString() : null);
            if (!p.parents().isEmpty()) {
                List<Map<String, Object>> parentRows = new ArrayList<>();
                for (var parent : p.parents()) {
                    parentRows.add(Map.of(
                            "id", parent.parentId().toString(),
                            "name", parent.parentName(),
                            "relation", parent.relation()));
                }
                row.put("parents", parentRows);
            }
            rows.add(row);
        }
        log.info("list_plans: user={} status={} limit={} → {} rows (summary mode)",
                userId, normalisedStatus, safeLimit, rows.size());
        return Map.of(
                "plans", rows,
                "count", rows.size(),
                "statusFilter", normalisedStatus == null ? "all" : normalisedStatus);
    }

    /**
     * English human label for the (status, phase) pair. Delivered to the LLM
     * so it never has to invent a translation — that was the source of the
     * "every plan gets the same label regardless of status" bug in earlier
     * builds. The LLM may translate the label into the user's language in its
     * reply, but MUST keep the same semantic meaning for the same status.
     */
    static String statusLabel(String status, String phase) {
        if (status == null) return "Unknown";
        return switch (status) {
            case "draft" -> "Draft (awaiting approval)";
            case "active" -> "executing".equals(phase) ? "Active (in progress)" : "Active";
            case "completed" -> "Completed";
            case "abandoned" -> "Abandoned";
            default -> status;
        };
    }

    @Tool(description = "Resume an existing plan by its id. Loads discovery facts and phase from the DB "
            + "and attaches the plan to the current chat session.")
    public Map<String, Object> resume_plan(
            @ToolParam(description = "Plan id to resume (UUID string).") String planId) {
        String sessionId = currentSessionId();
        UUID id = parseUuid(planId);
        if (id == null) {
            return Map.of("ok", false, "error", "invalid plan id: " + planId);
        }
        try {
            var resumed = planningModeService.resumeFromDb(id, sessionId);
            return Map.of(
                    "ok", true,
                    "planId", id.toString(),
                    "task", resumed.task(),
                    "phase", resumed.phase().name());
        } catch (IllegalStateException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @Tool(description = "Combine two or more existing plans into a new plan. Seeds the new plan's "
            + "discovery facts by merging the parents' known facts and excluded options (deduped). "
            + "Creates 'combines' plan_links rows. New plan starts in DISCOVERY phase.")
    public Map<String, Object> combine_plans(
            @ToolParam(description = "List of parent plan ids (UUID strings) to combine.") List<String> parentIds,
            @ToolParam(description = "Task description for the new combined plan.") String newTask) {
        String sessionId = currentSessionId();
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of("ok", false, "error", "parentIds must contain at least one id");
        }
        if (newTask == null || newTask.isBlank()) {
            return Map.of("ok", false, "error", "newTask must not be blank");
        }
        List<UUID> parents = new ArrayList<>();
        for (String s : parentIds) {
            UUID u = parseUuid(s);
            if (u == null) {
                return Map.of("ok", false, "error", "invalid plan id in list: " + s);
            }
            parents.add(u);
        }
        try {
            var result = planningModeService.combinePlans(parents, newTask, sessionId);
            return Map.of(
                    "ok", true,
                    "newPlanId", result.newPlanId().toString(),
                    "parents", parentIds,
                    "mergedKnownCount", result.mergedFacts().knownFacts().size(),
                    "mergedExcludedCount", result.mergedFacts().excludedOptions().size());
        } catch (IllegalStateException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private static String normaliseStatus(String status) {
        if (status == null) return null;
        String s = status.trim().toLowerCase();
        return switch (s) {
            case "", "all" -> null;
            case "draft", "active", "completed", "abandoned" -> s;
            default -> null;
        };
    }

    private static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Context helpers — use PlanningTools' ThreadLocal if set; else fall back to "bartek"
    // the same way TodoCommand does today. Auth TODO tracked.
    private static String currentSessionId() {
        String s = PlanningTools.getCurrentSessionId();
        return s != null ? s : "unknown";
    }

    private static String currentUserId() {
        // PlanningTools keeps USER_ID as private ThreadLocal — re-read the same
        // one by piggy-backing its setContext. Exposing a public getter would
        // be cleaner but is out of scope for this plan.
        String s = System.getProperty("kukuvaia.currentUser");
        if (s != null && !s.isBlank()) return s;
        return "bartek";
    }
}

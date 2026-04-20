package ai.kukuvaia.plans;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read + link-write access for the P21 plan registry.
 *
 * Design notes:
 * <ul>
 *   <li>Plan CRUD for the agent flow still lives in {@code PlanningTools} +
 *       {@code PlanningModeService} (they own phase transitions). This repo is
 *       for registry / browse / link operations only.</li>
 *   <li>User scoping is enforced at this layer — every read takes {@code userId}
 *       and filters. Callers must pass an authenticated user id, not trust the
 *       client.</li>
 *   <li>{@code discovery_facts} is returned as the raw JSONB string. Deserialisation
 *       into {@code DiscoveryFacts} happens in {@code PlanningModeService.resumeFromDb}
 *       — keeps this repo free of agent-layer dependencies.</li>
 * </ul>
 */
@Repository
public class PlansRepository {

    private static final Logger log = LoggerFactory.getLogger(PlansRepository.class);

    private static final int TASK_PREVIEW_MAX = 140;

    private static final RowMapper<PlanEntry> ENTRY_MAPPER = (rs, n) -> new PlanEntry(
            (UUID) rs.getObject("id"),
            rs.getString("session_id"),
            rs.getString("user_id"),
            nullableString(rs, "name"),
            truncate(rs.getString("task"), TASK_PREVIEW_MAX),
            rs.getString("status"),
            rs.getString("phase"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            List.of() // parents attached in a second pass by the service layer
    );

    private final JdbcTemplate jdbc;

    public PlansRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** List plans for a user, optionally filtered by a single status, newest first. */
    public List<PlanEntry> findByUser(String userId, String statusFilter, int limit) {
        if (userId == null || userId.isBlank()) return List.of();
        List<PlanEntry> rows;
        if (statusFilter == null || statusFilter.isBlank()) {
            rows = jdbc.query("""
                    SELECT id, session_id, user_id, name, task, status, phase, created_at, updated_at
                    FROM kukuvaia.plans
                    WHERE user_id = ?
                    ORDER BY updated_at DESC
                    LIMIT ?
                    """, ENTRY_MAPPER, userId, limit);
        } else {
            rows = jdbc.query("""
                    SELECT id, session_id, user_id, name, task, status, phase, created_at, updated_at
                    FROM kukuvaia.plans
                    WHERE user_id = ? AND status = ?
                    ORDER BY updated_at DESC
                    LIMIT ?
                    """, ENTRY_MAPPER, userId, statusFilter, limit);
        }
        return attachParents(rows);
    }

    /** Find a single plan by id, scoped to the owning user. */
    public Optional<PlanEntry> findById(UUID planId, String userId) {
        if (planId == null || userId == null) return Optional.empty();
        List<PlanEntry> rows = jdbc.query("""
                SELECT id, session_id, user_id, name, task, status, phase, created_at, updated_at
                FROM kukuvaia.plans
                WHERE id = ? AND user_id = ?
                """, ENTRY_MAPPER, planId, userId);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(attachParents(rows).get(0));
    }

    /**
     * Read-through access to the plan's full state (steps + discovery_facts + task + phase + user).
     * Used by {@code PlanningModeService.resumeFromDb} and {@code combinePlans}.
     */
    public Optional<PlanFullState> findFullState(UUID planId, String userId) {
        if (planId == null || userId == null) return Optional.empty();
        List<PlanFullState> rows = jdbc.query("""
                SELECT id, session_id, user_id, task, name, steps::text AS steps_json,
                       discovery_facts::text AS facts_json, status, phase,
                       created_at, updated_at
                FROM kukuvaia.plans
                WHERE id = ? AND user_id = ?
                """, (rs, n) -> new PlanFullState(
                (UUID) rs.getObject("id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("task"),
                nullableString(rs, "name"),
                nullableString(rs, "steps_json"),
                nullableString(rs, "facts_json"),
                rs.getString("status"),
                rs.getString("phase"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        ), planId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Insert a new plan row in a pre-approval phase — used by {@code combinePlans}.
     * Steps are empty (a placeholder `[]`); planning flow fills them later via
     * {@code createPlan}. Returns the new row's id.
     */
    @Transactional
    public UUID createPending(String sessionId, String userId, String task, String name,
                              String phase, String discoveryFactsJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kukuvaia.plans
                    (id, session_id, user_id, task, name, steps, status, phase, discovery_facts)
                VALUES (?, ?, ?, ?, ?, '[]'::jsonb, 'draft', ?, ?::jsonb)
                """, id, sessionId, userId, task, name, phase, discoveryFactsJson);
        log.info("Plan created: id={} user={} session={} phase={}", id, userId, sessionId, phase);
        return id;
    }

    /** Persist the DISCOVERY / DRAFTING / APPROVAL phase column on an existing row. */
    public int updatePhase(UUID planId, String phase) {
        return jdbc.update("""
                UPDATE kukuvaia.plans SET phase = ?, updated_at = NOW()
                WHERE id = ?
                """, phase, planId);
    }

    /** Persist the discovery_facts JSONB on an existing row. */
    public int updateDiscoveryFacts(UUID planId, String factsJson) {
        return jdbc.update("""
                UPDATE kukuvaia.plans SET discovery_facts = ?::jsonb, updated_at = NOW()
                WHERE id = ?
                """, factsJson, planId);
    }

    /** Insert a {@code plan_links} row. Caller validates user-scoping of both plans. */
    public UUID createLink(UUID sourcePlanId, UUID targetPlanId, String relation, String note) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kukuvaia.plan_links (id, source_plan_id, target_plan_id, relation, note)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (source_plan_id, target_plan_id, relation) DO NOTHING
                """, id, sourcePlanId, targetPlanId, relation, note);
        return id;
    }

    /** Returns true when all given plan ids exist AND belong to {@code userId}. */
    public boolean allBelongToUser(List<UUID> planIds, String userId) {
        if (planIds == null || planIds.isEmpty() || userId == null) return false;
        String placeholders = String.join(",", planIds.stream().map(p -> "?").toList());
        Object[] params = new Object[planIds.size() + 1];
        for (int i = 0; i < planIds.size(); i++) params[i] = planIds.get(i);
        params[planIds.size()] = userId;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kukuvaia.plans WHERE id IN (" + placeholders + ") AND user_id = ?",
                Integer.class, params);
        return count != null && count == planIds.size();
    }

    /**
     * Attach parent-link summaries to a batch of {@code PlanEntry}. One extra query
     * for the whole batch, not N+1.
     */
    private List<PlanEntry> attachParents(List<PlanEntry> rows) {
        if (rows.isEmpty()) return rows;
        Map<UUID, List<PlanEntry.PlanLinkSummary>> byChild = loadParentsBulk(
                rows.stream().map(PlanEntry::id).toList());
        List<PlanEntry> out = new ArrayList<>(rows.size());
        for (PlanEntry e : rows) {
            List<PlanEntry.PlanLinkSummary> parents = byChild.getOrDefault(e.id(), List.of());
            out.add(new PlanEntry(
                    e.id(), e.sessionId(), e.userId(), e.name(), e.taskPreview(),
                    e.status(), e.phase(), e.createdAt(), e.updatedAt(), parents));
        }
        return out;
    }

    private Map<UUID, List<PlanEntry.PlanLinkSummary>> loadParentsBulk(List<UUID> childIds) {
        if (childIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", childIds.stream().map(p -> "?").toList());
        Object[] params = childIds.toArray();
        Map<UUID, List<PlanEntry.PlanLinkSummary>> map = new HashMap<>();
        jdbc.query("""
                SELECT l.source_plan_id AS child, l.target_plan_id AS parent, l.relation,
                       p.name AS parent_name, p.task AS parent_task
                FROM kukuvaia.plan_links l
                JOIN kukuvaia.plans p ON p.id = l.target_plan_id
                WHERE l.source_plan_id IN (%s)
                """.formatted(placeholders),
                rs -> {
                    UUID child = (UUID) rs.getObject("child");
                    UUID parent = (UUID) rs.getObject("parent");
                    String relation = rs.getString("relation");
                    String pname = rs.getString("parent_name");
                    if (pname == null || pname.isBlank()) {
                        pname = truncate(rs.getString("parent_task"), 40);
                    }
                    map.computeIfAbsent(child, k -> new ArrayList<>())
                            .add(new PlanEntry.PlanLinkSummary(parent, relation, pname));
                }, params);
        return map;
    }

    private static String nullableString(ResultSet rs, String col) throws SQLException {
        String v = rs.getString(col);
        return v;
    }

    private static java.time.Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /** Full state used for resume / combine flows — includes raw JSONB strings. */
    public record PlanFullState(
            UUID id, String sessionId, String userId,
            String task, String name,
            String stepsJson, String discoveryFactsJson,
            String status, String phase,
            java.time.Instant createdAt, java.time.Instant updatedAt
    ) {}
}

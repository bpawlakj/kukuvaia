package ai.kukuvaia.agentrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate-backed CRUD + similarity for {@code kukuvaia.agent_runs}.
 *
 * <p>Mirrors the binding conventions used by {@code SmartMemoryRepository} (vector via
 * {@code ?::vector} string cast) and adds JSONB binding via {@link PGobject}. All write
 * methods log at INFO; reads are silent.
 */
@Component
public class AgentRunRepository {

    private static final Logger log = LoggerFactory.getLogger(AgentRunRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentRunRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<AgentRun> rowMapper = (rs, rowNum) -> {
        UUID id = UUID.fromString(rs.getString("id"));
        String threadIdStr = rs.getString("thread_id");
        String parentIdStr = rs.getString("parent_run_id");
        Timestamp queuedAt = rs.getTimestamp("queued_at");
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        Integer promptTokens = (Integer) rs.getObject("prompt_tokens");
        Integer completionTokens = (Integer) rs.getObject("completion_tokens");
        Integer totalTokens = (Integer) rs.getObject("total_tokens");

        return new AgentRun(
                id,
                threadIdStr != null ? UUID.fromString(threadIdStr) : null,
                parentIdStr != null ? UUID.fromString(parentIdStr) : null,
                rs.getString("invoker_name"),
                rs.getString("invoker_kind"),
                rs.getString("input_type"),
                readJson(rs.getString("input")),
                rs.getString("instructions"),
                rs.getString("persona_name"),
                rs.getString("task_class"),
                rs.getString("model"),
                AgentRunStatus.fromWire(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                readJson(rs.getString("output")),
                rs.getString("output_text"),
                promptTokens,
                completionTokens,
                totalTokens,
                queuedAt != null ? queuedAt.toInstant() : null,
                startedAt != null ? startedAt.toInstant() : null,
                completedAt != null ? completedAt.toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                readTextArray(rs.getArray("tags")),
                rs.getString("severity"));
    };

    /** Insert a fresh run row in {@code queued} state and return its assigned id. */
    public UUID insertQueued(AgentRunSpec spec) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO kukuvaia.agent_runs (
                            id, thread_id, parent_run_id,
                            invoker_name, invoker_kind, input_type, input,
                            instructions, persona_name, task_class,
                            status, queued_at, tags, severity
                        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, NOW(), ?, ?)
                        """,
                id,
                spec.threadId(),
                spec.parentRunId(),
                spec.invokerName(),
                spec.invokerKind(),
                spec.inputType(),
                writeJson(spec.input()),
                spec.llmFollowup() != null ? spec.llmFollowup().systemPrompt() : null,
                spec.personaName(),
                spec.taskClass(),
                AgentRunStatus.QUEUED.wireValue(),
                tagsArray(spec.tags()),
                spec.severity());
        log.info("agent_run inserted id={} invoker={}/{} input_type={}",
                id, spec.invokerKind(), spec.invokerName(), spec.inputType());
        return id;
    }

    public void markInProgress(UUID id) {
        jdbcTemplate.update("""
                UPDATE kukuvaia.agent_runs SET status = ?, started_at = NOW() WHERE id = ?
                """, AgentRunStatus.IN_PROGRESS.wireValue(), id);
    }

    public void markCompleted(UUID id, JsonNode output, String outputText, float[] embedding,
                              String model, Integer promptTokens, Integer completionTokens) {
        jdbcTemplate.update("""
                UPDATE kukuvaia.agent_runs SET
                    status = ?,
                    output = ?::jsonb,
                    output_text = ?,
                    embedding = CAST(? AS vector),
                    model = ?,
                    prompt_tokens = ?,
                    completion_tokens = ?,
                    completed_at = NOW()
                WHERE id = ?
                """,
                AgentRunStatus.COMPLETED.wireValue(),
                writeJson(output),
                outputText,
                embedding != null ? floatArrayToVectorString(embedding) : null,
                model,
                promptTokens,
                completionTokens,
                id);
    }

    public void markFailed(UUID id, String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE kukuvaia.agent_runs SET
                    status = ?, error_code = ?, error_message = ?, completed_at = NOW()
                WHERE id = ?
                """, AgentRunStatus.FAILED.wireValue(), errorCode, errorMessage, id);
    }

    public void markSkipped(UUID id, String reason) {
        jdbcTemplate.update("""
                UPDATE kukuvaia.agent_runs SET status = ?, error_message = ?, completed_at = NOW()
                WHERE id = ?
                """, AgentRunStatus.SKIPPED.wireValue(), reason, id);
    }

    public Optional<AgentRun> findById(UUID id) {
        var rows = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.agent_runs WHERE id = ?", rowMapper, id);
        return rows.stream().findFirst();
    }

    /** Browse runs by invoker_name in newest-first order. */
    public List<AgentRun> findByInvokerName(String invokerName, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM kukuvaia.agent_runs WHERE invoker_name = ?
                ORDER BY created_at DESC LIMIT ?
                """, rowMapper, invokerName, limit);
    }

    /**
     * Cosine similarity over the hnsw index. Filters: optional invoker name, age window,
     * and tag intersection ("tags && ?" — true if any tag overlaps).
     */
    public List<AgentRun> findSimilar(float[] queryVector, int topK,
                                      Integer maxAgeDays, String filterInvokerName,
                                      List<String> filterTags) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM kukuvaia.agent_runs
                WHERE embedding IS NOT NULL
                  AND status = 'completed'
                """);
        List<Object> params = new ArrayList<>();
        if (maxAgeDays != null && maxAgeDays > 0) {
            sql.append("  AND created_at > NOW() - CAST(? || ' days' AS INTERVAL)\n");
            params.add(maxAgeDays.toString());
        }
        if (filterInvokerName != null && !filterInvokerName.isBlank()) {
            sql.append("  AND invoker_name = ?\n");
            params.add(filterInvokerName);
        }
        if (filterTags != null && !filterTags.isEmpty()) {
            sql.append("  AND tags && ?\n");
            params.add(tagsArray(filterTags));
        }
        sql.append("ORDER BY embedding <=> CAST(? AS vector) LIMIT ?\n");
        params.add(floatArrayToVectorString(queryVector));
        params.add(topK);
        return jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
    }

    /** Wipe a run row — used by tests; production never deletes. */
    public int deleteById(UUID id) {
        return jdbcTemplate.update("DELETE FROM kukuvaia.agent_runs WHERE id = ?", id);
    }

    // --- helpers ---------------------------------------------------------

    /** JSONB values are bound as raw strings + {@code ?::jsonb} cast in SQL — matches the
     *  convention in {@code SessionRepository}, {@code ModelRepository}, etc. Avoids pulling
     *  the postgres driver into kukuvaia-core compile classpath. */
    private String writeJson(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSONB payload", e);
        }
    }

    private JsonNode readJson(String raw) {
        if (raw == null) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse JSONB payload from agent_runs", e);
        }
    }

    private static String floatArrayToVectorString(float[] vector) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String[] tagsArray(List<String> tags) {
        if (tags == null) return new String[0];
        return tags.toArray(String[]::new);
    }

    private static List<String> readTextArray(Array array) throws SQLException {
        if (array == null) return List.of();
        Object raw = array.getArray();
        if (raw instanceof String[] arr) {
            return List.of(arr);
        }
        return List.of();
    }

    /** Test-only helper: ensures Instant fields can be queried without a row mapper. */
    public Optional<Instant> findCompletedAt(UUID id) {
        return findById(id).map(AgentRun::completedAt);
    }
}

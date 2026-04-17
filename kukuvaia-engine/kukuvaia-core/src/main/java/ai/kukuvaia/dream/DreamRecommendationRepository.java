package ai.kukuvaia.dream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Component
public class DreamRecommendationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<DreamRecommendationRecord> rowMapper = (rs, rowNum) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new DreamRecommendationRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("report_id")),
                rs.getString("type"),
                rs.getString("priority"),
                rs.getString("description"),
                rs.getString("suggested_action"),
                rs.getDouble("confidence"),
                parseMap(rs.getString("evidence")),
                rs.getString("status"),
                resolvedAt != null ? resolvedAt.toInstant() : null,
                rs.getString("resolved_by"),
                rs.getString("rejection_reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    };

    public DreamRecommendationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public DreamRecommendationRecord save(UUID reportId, String type, String priority,
                                           String description, String suggestedAction,
                                           double confidence, Map<String, Object> evidence) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.dream_recommendations
                    (id, report_id, type, priority, description, suggested_action, confidence, evidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, NOW())
                """,
                id.toString(), reportId.toString(), type, priority,
                description, suggestedAction, confidence, toJsonMap(evidence));
        return findById(id).orElseThrow();
    }

    public Optional<DreamRecommendationRecord> findById(UUID id) {
        var results = jdbcTemplate.query("SELECT * FROM kukuvaia.dream_recommendations WHERE id = ?", rowMapper, id.toString());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<DreamRecommendationRecord> findByReportId(UUID reportId) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.dream_recommendations WHERE report_id = ? ORDER BY priority, type",
                rowMapper, reportId.toString());
    }

    public List<DreamRecommendationRecord> findPending() {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.dream_recommendations WHERE status = 'pending' ORDER BY created_at DESC",
                rowMapper);
    }

    public int accept(UUID id, String resolvedBy) {
        return jdbcTemplate.update(
                "UPDATE kukuvaia.dream_recommendations SET status = 'accepted', resolved_at = NOW(), resolved_by = ? WHERE id = ? AND status = 'pending'",
                resolvedBy, id.toString());
    }

    public int reject(UUID id, String resolvedBy, String reason) {
        return jdbcTemplate.update(
                "UPDATE kukuvaia.dream_recommendations SET status = 'rejected', resolved_at = NOW(), resolved_by = ?, rejection_reason = ? WHERE id = ? AND status = 'pending'",
                resolvedBy, reason, id.toString());
    }

    private String toJsonMap(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map != null ? map : Map.of()); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return Map.of(); }
    }
}

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
public class DreamReportRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<DreamReportRecord> rowMapper = (rs, rowNum) -> {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new DreamReportRecord(
                UUID.fromString(rs.getString("id")),
                rs.getTimestamp("started_at").toInstant(),
                completedAt != null ? completedAt.toInstant() : null,
                rs.getString("status"),
                rs.getInt("token_cost"),
                parseList(rs.getString("models_used")),
                parseMap(rs.getString("health_snapshot")),
                rs.getString("summary"),
                rs.getTimestamp("created_at").toInstant()
        );
    };

    public DreamReportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public DreamReportRecord create() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO kukuvaia.dream_reports (id, started_at, status, created_at) VALUES (?, ?, 'running', ?)",
                id.toString(), Timestamp.from(now), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    public void complete(UUID id, String summary, int tokenCost, List<String> modelsUsed,
                         Map<String, Object> healthSnapshot) {
        jdbcTemplate.update("""
                UPDATE kukuvaia.dream_reports SET status = 'completed', completed_at = NOW(),
                    summary = ?, token_cost = ?, models_used = ?::jsonb, health_snapshot = ?::jsonb
                WHERE id = ?
                """, summary, tokenCost, toJson(modelsUsed), toJsonMap(healthSnapshot), id.toString());
    }

    public void fail(UUID id, String error) {
        jdbcTemplate.update(
                "UPDATE kukuvaia.dream_reports SET status = 'failed', completed_at = NOW(), summary = ? WHERE id = ?",
                error, id.toString());
    }

    public Optional<DreamReportRecord> findById(UUID id) {
        var results = jdbcTemplate.query("SELECT * FROM kukuvaia.dream_reports WHERE id = ?", rowMapper, id.toString());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public Optional<DreamReportRecord> findLatest() {
        var results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.dream_reports ORDER BY started_at DESC LIMIT 1", rowMapper);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<DreamReportRecord> findAll(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.dream_reports ORDER BY started_at DESC LIMIT ?", rowMapper, limit);
    }

    private String toJson(List<String> list) {
        try { return objectMapper.writeValueAsString(list != null ? list : List.of()); }
        catch (JsonProcessingException e) { return "[]"; }
    }

    private String toJsonMap(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map != null ? map : Map.of()); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    private List<String> parseList(String json) {
        if (json == null) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return Map.of(); }
    }
}

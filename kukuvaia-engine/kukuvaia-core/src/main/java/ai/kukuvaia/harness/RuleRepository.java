package ai.kukuvaia.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class RuleRepository {

    private static final Logger log = LoggerFactory.getLogger(RuleRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<RuleRecord> rowMapper = (rs, rowNum) -> new RuleRecord(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("rule_set_id")),
            rs.getString("key"),
            rs.getString("type"),
            rs.getString("content"),
            parseTags(rs.getString("tags")),
            parseActivation(rs.getString("activation")),
            rs.getInt("priority"),
            rs.getBoolean("enabled"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public RuleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RuleRecord save(UUID ruleSetId, String key, String type, String content,
                           List<String> tags, Map<String, Object> activation, int priority) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.rules (id, rule_set_id, key, type, content, tags, activation, priority, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                id.toString(), ruleSetId.toString(), key, type, content,
                toJson(tags), toJsonMap(activation), priority,
                Timestamp.from(now), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    public Optional<RuleRecord> findById(UUID id) {
        var results = jdbcTemplate.query("SELECT * FROM kukuvaia.rules WHERE id = ?", rowMapper, id.toString());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<RuleRecord> findByRuleSetId(UUID ruleSetId) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.rules WHERE rule_set_id = ? AND enabled = TRUE ORDER BY priority, key",
                rowMapper, ruleSetId.toString());
    }

    public int delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM kukuvaia.rules WHERE id = ?", id.toString());
    }

    private String toJson(List<String> list) {
        try { return objectMapper.writeValueAsString(list != null ? list : List.of()); }
        catch (JsonProcessingException e) { return "[]"; }
    }

    private String toJsonMap(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map != null ? map : Map.of("always", true)); }
        catch (JsonProcessingException e) { return "{\"always\":true}"; }
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return List.of(); }
    }

    private Map<String, Object> parseActivation(String json) {
        if (json == null || json.isBlank()) return Map.of("always", true);
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { return Map.of("always", true); }
    }
}

package ai.kukuvaia.provider.registry;

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

/**
 * CRUD repository for {@code kukuvaia.providers} table.
 * All queries parameterized — SQL injection eliminated by design.
 */
@Component
public class ProviderRepository {

    private static final Logger log = LoggerFactory.getLogger(ProviderRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<ProviderRecord> rowMapper = (rs, rowNum) -> new ProviderRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("base_url"),
            rs.getString("api_key_ref"),
            rs.getBoolean("enabled"),
            rs.getInt("priority"),
            parseJsonb(rs.getString("config")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public ProviderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ProviderRecord save(String name, String type, String baseUrl,
                               String apiKeyRef, int priority, Map<String, Object> config) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String configJson = toJsonb(config);

        jdbcTemplate.update("""
                INSERT INTO kukuvaia.providers (id, name, type, base_url, api_key_ref, priority, config, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                id, name, type, baseUrl, apiKeyRef, priority,
                configJson, Timestamp.from(now), Timestamp.from(now));

        log.info("Created provider: name={}, type={}", name, type);
        return findById(id).orElseThrow();
    }

    public Optional<ProviderRecord> findById(UUID id) {
        List<ProviderRecord> results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.providers WHERE id = ?",
                rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public Optional<ProviderRecord> findByName(String name) {
        List<ProviderRecord> results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.providers WHERE name = ?",
                rowMapper, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<ProviderRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.providers ORDER BY priority DESC, name",
                rowMapper);
    }

    public List<ProviderRecord> findEnabled() {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.providers WHERE enabled = TRUE ORDER BY priority DESC, name",
                rowMapper);
    }

    public int update(UUID id, UpdateProviderRequest request) {
        var sb = new StringBuilder("UPDATE kukuvaia.providers SET updated_at = NOW()");
        var params = new java.util.ArrayList<>();

        if (request.name() != null) { sb.append(", name = ?"); params.add(request.name()); }
        if (request.type() != null) { sb.append(", type = ?"); params.add(request.type()); }
        if (request.baseUrl() != null) { sb.append(", base_url = ?"); params.add(request.baseUrl()); }
        if (request.apiKeyRef() != null) { sb.append(", api_key_ref = ?"); params.add(request.apiKeyRef()); }
        if (request.enabled() != null) { sb.append(", enabled = ?"); params.add(request.enabled()); }
        if (request.priority() != null) { sb.append(", priority = ?"); params.add(request.priority()); }
        if (request.config() != null) { sb.append(", config = ?::jsonb"); params.add(toJsonb(request.config())); }

        sb.append(" WHERE id = ?");
        params.add(id);

        return jdbcTemplate.update(sb.toString(), params.toArray());
    }

    public int delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM kukuvaia.providers WHERE id = ?", id);
    }

    public long count() {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kukuvaia.providers", Long.class);
        return result != null ? result : 0;
    }

    private String toJsonb(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map != null ? map : Map.of());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJsonb(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSONB config: {}", e.getMessage());
            return Map.of();
        }
    }
}

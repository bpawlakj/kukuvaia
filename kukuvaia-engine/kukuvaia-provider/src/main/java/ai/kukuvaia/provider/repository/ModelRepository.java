package ai.kukuvaia.provider.repository;

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
import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.dto.UpdateModelRequest;

/**
 * CRUD repository for {@code kukuvaia.models} table.
 * All queries parameterized — SQL injection eliminated by design.
 */
@Component
public class ModelRepository {

    private static final Logger log = LoggerFactory.getLogger(ModelRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<ModelRecord> rowMapper = (rs, rowNum) -> {
        Timestamp discoveredAt = rs.getTimestamp("discovered_at");
        Integer contextWindow = rs.getObject("context_window", Integer.class);

        return new ModelRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("provider_id")),
                rs.getString("model_id"),
                rs.getString("display_name"),
                parseCapabilities(rs.getString("capabilities")),
                rs.getString("tier"),
                rs.getInt("max_tokens"),
                contextWindow,
                rs.getBoolean("enabled"),
                parseJsonb(rs.getString("config")),
                discoveredAt != null ? discoveredAt.toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    };

    public ModelRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ModelRecord save(UUID providerId, String modelId, String displayName,
                            List<String> capabilities, String tier, int maxTokens,
                            Integer contextWindow, Instant discoveredAt) {
        return save(providerId, modelId, displayName, capabilities, tier, maxTokens,
                contextWindow, discoveredAt, Map.of());
    }

    public ModelRecord save(UUID providerId, String modelId, String displayName,
                            List<String> capabilities, String tier, int maxTokens,
                            Integer contextWindow, Instant discoveredAt,
                            Map<String, Object> config) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO kukuvaia.models
                    (id, provider_id, model_id, display_name, capabilities, tier, max_tokens, context_window, config, discovered_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                id, providerId, modelId, displayName,
                toJson(capabilities), tier, maxTokens, contextWindow,
                toJsonMap(config),
                discoveredAt != null ? Timestamp.from(discoveredAt) : null,
                Timestamp.from(now), Timestamp.from(now));

        log.info("Created model: modelId={}, providerId={}, configKeys={}",
                modelId, providerId, config != null ? config.keySet() : List.of());
        return findById(id).orElseThrow();
    }

    public Optional<ModelRecord> findById(UUID id) {
        List<ModelRecord> results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.models WHERE id = ?",
                rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public Optional<ModelRecord> findByModelId(String modelId) {
        List<ModelRecord> results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.models WHERE model_id = ? AND enabled = TRUE LIMIT 1",
                rowMapper, modelId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<ModelRecord> findByProviderId(UUID providerId) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.models WHERE provider_id = ? ORDER BY model_id",
                rowMapper, providerId);
    }

    public List<ModelRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.models ORDER BY tier, model_id",
                rowMapper);
    }

    public List<ModelRecord> findEnabled() {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.models WHERE enabled = TRUE ORDER BY tier, model_id",
                rowMapper);
    }

    public List<ModelRecord> findByTier(String tier) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.models WHERE tier = ? AND enabled = TRUE ORDER BY model_id",
                rowMapper, tier);
    }

    public int update(UUID id, UpdateModelRequest request) {
        var sb = new StringBuilder("UPDATE kukuvaia.models SET updated_at = NOW()");
        var params = new java.util.ArrayList<>();

        if (request.displayName() != null) { sb.append(", display_name = ?"); params.add(request.displayName()); }
        if (request.capabilities() != null) { sb.append(", capabilities = ?::jsonb"); params.add(toJson(request.capabilities())); }
        if (request.tier() != null) { sb.append(", tier = ?"); params.add(request.tier()); }
        if (request.maxTokens() != null) { sb.append(", max_tokens = ?"); params.add(request.maxTokens()); }
        if (request.contextWindow() != null) { sb.append(", context_window = ?"); params.add(request.contextWindow()); }
        if (request.enabled() != null) { sb.append(", enabled = ?"); params.add(request.enabled()); }
        if (request.config() != null) { sb.append(", config = ?::jsonb"); params.add(toJsonMap(request.config())); }

        sb.append(" WHERE id = ?");
        params.add(id);

        return jdbcTemplate.update(sb.toString(), params.toArray());
    }

    public int delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM kukuvaia.models WHERE id = ?", id);
    }

    public long countByProviderId(UUID providerId) {
        Long result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kukuvaia.models WHERE provider_id = ?",
                Long.class, providerId);
        return result != null ? result : 0;
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : List.of());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String toJsonMap(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map != null ? map : Map.of());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private List<String> parseCapabilities(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse capabilities JSON: {}", e.getMessage());
            return List.of();
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

package ai.kukuvaia.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD over {@code kukuvaia.mcp_connections}.
 *
 * <p>No caching here — cache duties belong to {@link McpConnectionsCache}
 * so request-path reads are consistent + O(1). The repository is the
 * single DB gateway for admin CRUD endpoints + cache refresh.
 */
@Repository
public class McpConnectionsRepository {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionsRepository.class);
    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper jsonMapper;

    private final RowMapper<McpConnection> rowMapper = (rs, n) -> new McpConnection(
            (UUID) rs.getObject("id"),
            rs.getString("name"),
            rs.getString("url"),
            rs.getString("sse_endpoint"),
            parseHeaders(rs.getString("headers")),
            rs.getBoolean("enabled"),
            rs.getString("description"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    public McpConnectionsRepository(JdbcTemplate jdbc, ObjectMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public List<McpConnection> findAllEnabled() {
        return jdbc.query("""
                SELECT id, name, url, sse_endpoint, headers::text AS headers, enabled, description,
                       created_at, updated_at
                FROM kukuvaia.mcp_connections
                WHERE enabled = true
                ORDER BY name
                """, rowMapper);
    }

    public List<McpConnection> findAll() {
        return jdbc.query("""
                SELECT id, name, url, sse_endpoint, headers::text AS headers, enabled, description,
                       created_at, updated_at
                FROM kukuvaia.mcp_connections
                ORDER BY name
                """, rowMapper);
    }

    public Optional<McpConnection> findById(UUID id) {
        var rows = jdbc.query("""
                SELECT id, name, url, sse_endpoint, headers::text AS headers, enabled, description,
                       created_at, updated_at
                FROM kukuvaia.mcp_connections
                WHERE id = ?
                """, rowMapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<McpConnection> findByName(String name) {
        var rows = jdbc.query("""
                SELECT id, name, url, sse_endpoint, headers::text AS headers, enabled, description,
                       created_at, updated_at
                FROM kukuvaia.mcp_connections
                WHERE name = ?
                """, rowMapper, name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public UUID create(String name, String url, String sseEndpoint,
                       Map<String, String> headers, boolean enabled, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kukuvaia.mcp_connections
                    (id, name, url, sse_endpoint, headers, enabled, description)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, id, name, url, sseEndpoint, toJson(headers), enabled, description);
        log.info("MCP connection created: name={} id={}", name, id);
        return id;
    }

    public int update(UUID id, String url, String sseEndpoint,
                      Map<String, String> headers, Boolean enabled, String description) {
        int affected = jdbc.update("""
                UPDATE kukuvaia.mcp_connections
                   SET url = COALESCE(?, url),
                       sse_endpoint = COALESCE(?, sse_endpoint),
                       headers = COALESCE(?::jsonb, headers),
                       enabled = COALESCE(?, enabled),
                       description = COALESCE(?, description),
                       updated_at = NOW()
                 WHERE id = ?
                """, url, sseEndpoint, headers == null ? null : toJson(headers),
                enabled, description, id);
        if (affected > 0) {
            log.info("MCP connection updated: id={}", id);
        }
        return affected;
    }

    public int delete(UUID id) {
        int affected = jdbc.update("DELETE FROM kukuvaia.mcp_connections WHERE id = ?", id);
        if (affected > 0) {
            log.info("MCP connection deleted: id={}", id);
        }
        return affected;
    }

    private Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> m = jsonMapper.readValue(json, HEADERS_TYPE);
            return m == null ? Map.of() : m;
        } catch (JsonProcessingException e) {
            log.warn("mcp_connections.headers JSON parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "{}";
        try {
            return jsonMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("headers must be serialisable as JSON object", e);
        }
    }

    private static java.time.Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

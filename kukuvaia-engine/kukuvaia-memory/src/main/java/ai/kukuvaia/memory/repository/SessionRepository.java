package ai.kukuvaia.memory.repository;

import ai.kukuvaia.memory.model.KukuvaiaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD for the {@code kukuvaia.sessions} table.
 * All queries parameterized — SQL injection eliminated by design.
 */
@Component
public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<KukuvaiaSession> ROW_MAPPER = (rs, rowNum) -> new KukuvaiaSession(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("name"),
            parseMetadata(rs.getString("metadata")),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public SessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Insert a new session.
     */
    public void save(String id, String userId, String name, String metadataJson) {
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.sessions (id, user_id, name, metadata)
                VALUES (?, ?, ?, ?::jsonb)
                """, id, userId, name, metadataJson);
        log.info("Created session '{}' for user '{}'", id, userId);
    }

    /**
     * Ensure a session row exists. No-op if it already does.
     * Also ensures the user row exists (required by FK).
     */
    public void ensureExists(String sessionId, String userId) {
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.users (id, display_name)
                VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET last_seen_at = NOW()
                """, userId, userId);
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.sessions (id, user_id)
                VALUES (?, ?)
                ON CONFLICT (id) DO NOTHING
                """, sessionId, userId);
    }

    /**
     * Find session by primary key.
     */
    public Optional<KukuvaiaSession> findById(String id) {
        var results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.sessions WHERE id = ?",
                ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Find all sessions for a user, most recently updated first.
     */
    public List<KukuvaiaSession> findByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.sessions WHERE user_id = ? ORDER BY updated_at DESC",
                ROW_MAPPER, userId);
    }

    /**
     * Update the session name.
     */
    public void updateName(String id, String name) {
        jdbcTemplate.update(
                "UPDATE kukuvaia.sessions SET name = ?, updated_at = NOW() WHERE id = ?",
                name, id);
        log.debug("Updated name for session '{}'", id);
    }

    /**
     * Get the extraction cursor (index of last processed message) for a session.
     * Returns 0 if session doesn't exist or cursor is not set.
     */
    public int getExtractionCursor(String sessionId) {
        var results = jdbcTemplate.queryForList(
                "SELECT extraction_cursor_idx FROM kukuvaia.sessions WHERE id = ?",
                Integer.class, sessionId);
        if (results.isEmpty() || results.getFirst() == null) return 0;
        return results.getFirst();
    }

    /**
     * Update the extraction cursor after successful extraction.
     */
    public void updateExtractionCursor(String sessionId, int cursor) {
        int updated = jdbcTemplate.update(
                "UPDATE kukuvaia.sessions SET extraction_cursor_idx = ?, updated_at = NOW() WHERE id = ?",
                cursor, sessionId);
        if (updated == 0) {
            log.debug("Session '{}' not in kukuvaia.sessions, cursor not persisted", sessionId);
        }
    }

    /**
     * Archive a session by setting its status to 'archived'.
     */
    public boolean archive(String id) {
        int rows = jdbcTemplate.update(
                "UPDATE kukuvaia.sessions SET status = 'archived', updated_at = NOW() WHERE id = ?", id);
        if (rows > 0) log.info("Archived session '{}'", id);
        return rows > 0;
    }

    private static Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            var map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            return map;
        } catch (Exception e) {
            log.warn("Failed to parse session metadata JSON: {}", e.getMessage());
            return Map.of();
        }
    }
}

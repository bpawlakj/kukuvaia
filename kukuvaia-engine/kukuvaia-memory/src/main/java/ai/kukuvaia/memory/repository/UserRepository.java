package ai.kukuvaia.memory.repository;

import ai.kukuvaia.memory.model.KukuvaiaUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD for the {@code kukuvaia.users} table.
 * All queries parameterized — SQL injection eliminated by design.
 */
@Component
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<KukuvaiaUser> ROW_MAPPER = (rs, rowNum) -> new KukuvaiaUser(
            rs.getString("id"),
            rs.getString("display_name"),
            parsePreferences(rs.getString("preferences")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_seen_at") != null ? rs.getTimestamp("last_seen_at").toInstant() : null
    );

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Upsert a user — insert or update display name and preferences on conflict.
     */
    public void save(String id, String displayName, String preferencesJson) {
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.users (id, display_name, preferences)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT (id) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    preferences = EXCLUDED.preferences,
                    last_seen_at = NOW()
                """, id, displayName, preferencesJson);
        log.info("Saved user '{}'", id);
    }

    /**
     * Find user by primary key.
     */
    public Optional<KukuvaiaUser> findById(String id) {
        var results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.users WHERE id = ?",
                ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Find all users.
     */
    public List<KukuvaiaUser> findAll() {
        return jdbcTemplate.query("SELECT * FROM kukuvaia.users", ROW_MAPPER);
    }

    /**
     * Touch last_seen_at timestamp for the given user.
     */
    public void updateLastSeen(String id) {
        jdbcTemplate.update(
                "UPDATE kukuvaia.users SET last_seen_at = NOW() WHERE id = ?", id);
        log.debug("Updated last_seen_at for user '{}'", id);
    }

    private static Map<String, Object> parsePreferences(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            var map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            return map;
        } catch (Exception e) {
            log.warn("Failed to parse preferences JSON: {}", e.getMessage());
            return Map.of();
        }
    }
}

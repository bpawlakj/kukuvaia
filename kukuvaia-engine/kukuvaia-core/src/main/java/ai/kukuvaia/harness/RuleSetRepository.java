package ai.kukuvaia.harness;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RuleSetRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<RuleSetRecord> ROW_MAPPER = (rs, rowNum) -> {
        String ownerId = rs.getString("owner_id");
        return new RuleSetRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("scope"),
                rs.getString("owner_type"),
                ownerId,
                rs.getInt("priority"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    };

    public RuleSetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RuleSetRecord save(String name, String description, String scope,
                              String ownerType, String ownerId, int priority) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.rule_sets (id, name, description, scope, owner_type, owner_id, priority, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(), name, description, scope, ownerType,
                ownerId, priority,
                Timestamp.from(now), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    public Optional<RuleSetRecord> findById(UUID id) {
        var results = jdbcTemplate.query("SELECT * FROM kukuvaia.rule_sets WHERE id = ?", ROW_MAPPER, id.toString());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<RuleSetRecord> findByScope(String scope) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.rule_sets WHERE scope = ? AND enabled = TRUE ORDER BY priority",
                ROW_MAPPER, scope);
    }

    public List<RuleSetRecord> findByOwner(String ownerType, UUID ownerId) {
        return findByOwner(ownerType, ownerId.toString());
    }

    public List<RuleSetRecord> findByOwner(String ownerType, String ownerId) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.rule_sets WHERE owner_type = ? AND owner_id = ? AND enabled = TRUE ORDER BY priority",
                ROW_MAPPER, ownerType, ownerId);
    }

    public List<RuleSetRecord> findAll() {
        return jdbcTemplate.query("SELECT * FROM kukuvaia.rule_sets ORDER BY scope, priority", ROW_MAPPER);
    }

    public int delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM kukuvaia.rule_sets WHERE id = ?", id.toString());
    }
}

package ai.kukuvaia.provider.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ai.kukuvaia.provider.model.ModelRoleRecord;

/**
 * CRUD repository for {@code kukuvaia.model_roles} table.
 * Each role maps to exactly one model. Roles are unique — upsert semantics.
 */
@Component
public class ModelRoleRepository {

    private static final Logger log = LoggerFactory.getLogger(ModelRoleRepository.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<ModelRoleRecord> ROW_MAPPER = (rs, rowNum) -> new ModelRoleRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("role"),
            UUID.fromString(rs.getString("model_id")),
            rs.getString("description"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    public ModelRoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Assign or reassign a role to a model. Upsert: if role exists, update model_id.
     */
    public ModelRoleRecord upsert(String role, UUID modelId, String description) {
        Instant now = Instant.now();

        int updated = jdbcTemplate.update("""
                UPDATE kukuvaia.model_roles SET model_id = ?, description = ?, updated_at = ?
                WHERE role = ?
                """,
                modelId, description, Timestamp.from(now), role);

        if (updated == 0) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO kukuvaia.model_roles (id, role, model_id, description, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    id, role, modelId, description,
                    Timestamp.from(now), Timestamp.from(now));
            log.info("Assigned role '{}' to model {}", role, modelId);
        } else {
            log.info("Reassigned role '{}' to model {}", role, modelId);
        }

        return findByRole(role).orElseThrow();
    }

    public Optional<ModelRoleRecord> findByRole(String role) {
        List<ModelRoleRecord> results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.model_roles WHERE role = ?",
                ROW_MAPPER, role);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<ModelRoleRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.model_roles ORDER BY role",
                ROW_MAPPER);
    }

    public List<ModelRoleRecord> findByModelId(UUID modelId) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.model_roles WHERE model_id = ?",
                ROW_MAPPER, modelId);
    }

    public int delete(String role) {
        int deleted = jdbcTemplate.update("DELETE FROM kukuvaia.model_roles WHERE role = ?", role);
        if (deleted > 0) log.info("Removed role assignment: {}", role);
        return deleted;
    }

    public long count() {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kukuvaia.model_roles", Long.class);
        return result != null ? result : 0;
    }
}

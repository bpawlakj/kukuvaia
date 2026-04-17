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
public class GroupRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<GroupRecord> ROW_MAPPER = (rs, rowNum) -> {
        String parentId = rs.getString("parent_id");
        return new GroupRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("description"),
                parentId != null ? UUID.fromString(parentId) : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    };

    public GroupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public GroupRecord save(String name, String description, UUID parentId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO kukuvaia.groups (id, name, description, parent_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id.toString(), name, description, parentId != null ? parentId.toString() : null,
                Timestamp.from(now), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    public Optional<GroupRecord> findById(UUID id) {
        var results = jdbcTemplate.query("SELECT * FROM kukuvaia.groups WHERE id = ?", ROW_MAPPER, id.toString());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<GroupRecord> findAll() {
        return jdbcTemplate.query("SELECT * FROM kukuvaia.groups ORDER BY name", ROW_MAPPER);
    }

    public List<UUID> findGroupIdsByUserId(String userId) {
        return jdbcTemplate.queryForList(
                "SELECT group_id FROM kukuvaia.user_groups WHERE user_id = ?",
                String.class, userId
        ).stream().map(UUID::fromString).toList();
    }

    public void addMember(UUID groupId, String userId, String role) {
        jdbcTemplate.update(
                "INSERT INTO kukuvaia.user_groups (user_id, group_id, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                userId, groupId.toString(), role);
    }

    public void removeMember(UUID groupId, String userId) {
        jdbcTemplate.update(
                "DELETE FROM kukuvaia.user_groups WHERE group_id = ? AND user_id = ?",
                groupId.toString(), userId);
    }

    public int delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM kukuvaia.groups WHERE id = ?", id.toString());
    }
}

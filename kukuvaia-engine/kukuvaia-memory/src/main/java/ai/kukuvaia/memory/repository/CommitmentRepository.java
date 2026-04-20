package ai.kukuvaia.memory.repository;

import ai.kukuvaia.memory.model.Commitment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC access to {@code kukuvaia.commitments}. Lightweight — no pgvector or
 * embeddings; commitments are short actionable phrases, not semantic memories.
 */
@Repository
public class CommitmentRepository {

    private static final Logger log = LoggerFactory.getLogger(CommitmentRepository.class);

    private final JdbcTemplate jdbc;

    public CommitmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Commitment> ROW = (rs, i) -> new Commitment(
            (UUID) rs.getObject("id"),
            rs.getString("user_id"),
            rs.getString("session_id"),
            rs.getString("summary"),
            rs.getString("detail"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getString("due_hint"),
            ts(rs.getTimestamp("due_at")),
            ts(rs.getTimestamp("created_at")),
            ts(rs.getTimestamp("updated_at")),
            ts(rs.getTimestamp("completed_at")),
            rs.getDouble("relevance_score")
    );

    /** Create a new commitment. Returns the generated UUID. */
    public UUID create(String userId, String sessionId, String summary, String detail,
                       String source, String dueHint) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kukuvaia.commitments
                (id, user_id, session_id, summary, detail, status, source, due_hint)
                VALUES (?, ?, ?, ?, ?, 'open', ?, ?)
                """, id, userId, sessionId, summary, detail, source, dueHint);
        log.info("Commitment created: id={} user={} source={} summary={}", id, userId, source, summary);
        return id;
    }

    /** Open (status = 'open' or 'in_progress') commitments for this user. */
    public List<Commitment> findOpenByUser(String userId, int limit) {
        return jdbc.query("""
                SELECT * FROM kukuvaia.commitments
                WHERE user_id = ? AND status IN ('open', 'in_progress')
                ORDER BY relevance_score DESC, updated_at DESC
                LIMIT ?
                """, ROW, userId, limit);
    }

    /** All commitments for a session regardless of status. */
    public List<Commitment> findBySession(String sessionId) {
        return jdbc.query("""
                SELECT * FROM kukuvaia.commitments
                WHERE session_id = ?
                ORDER BY updated_at DESC
                """, ROW, sessionId);
    }

    public Optional<Commitment> findById(UUID id) {
        return jdbc.query("SELECT * FROM kukuvaia.commitments WHERE id = ?", ROW, id)
                .stream().findFirst();
    }

    /**
     * Update status. Returns number of rows affected (0 if id unknown or not owned by user).
     * Sets completed_at when status transitions to 'done'.
     */
    public int markStatus(UUID id, String userId, String status) {
        int updated;
        if ("done".equals(status)) {
            updated = jdbc.update("""
                    UPDATE kukuvaia.commitments
                    SET status = 'done', completed_at = NOW(), updated_at = NOW()
                    WHERE id = ? AND user_id = ?
                    """, id, userId);
        } else {
            updated = jdbc.update("""
                    UPDATE kukuvaia.commitments
                    SET status = ?, updated_at = NOW()
                    WHERE id = ? AND user_id = ?
                    """, status, id, userId);
        }
        log.info("Commitment markStatus: id={} status={} updated={}", id, status, updated);
        return updated;
    }

    /** Bump relevance back to 1.0 on access. */
    public void touchAccess(UUID id) {
        jdbc.update("""
                UPDATE kukuvaia.commitments
                SET relevance_score = 1.0, updated_at = NOW()
                WHERE id = ?
                """, id);
    }

    private static java.time.Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

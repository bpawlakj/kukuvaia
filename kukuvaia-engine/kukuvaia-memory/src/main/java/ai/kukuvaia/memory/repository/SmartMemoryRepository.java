package ai.kukuvaia.memory.repository;

import ai.kukuvaia.memory.model.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + full-text search for the {@code kukuvaia.memories} table.
 * Extends the original MemoryRepository with memory type classification,
 * relevance scoring, access tracking, session binding, and expiration.
 * All queries parameterized — SQL injection eliminated by design.
 */
@Component
public class SmartMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(SmartMemoryRepository.class);

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<MemoryEntry> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp lastAccessed = rs.getTimestamp("last_accessed_at");
        Timestamp expiresAt = rs.getTimestamp("expires_at");

        return new MemoryEntry(
                UUID.fromString(rs.getString("id")),
                rs.getString("user_id"),
                rs.getString("category"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("content"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("memory_type"),
                rs.getDouble("relevance_score"),
                rs.getInt("access_count"),
                lastAccessed != null ? lastAccessed.toInstant() : null,
                rs.getString("session_id"),
                expiresAt != null ? expiresAt.toInstant() : null
        );
    };

    public SmartMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Upsert a memory entry with new fields (memoryType, relevanceScore, sessionId).
     * Conflicts on (user_id, name) update category, description, content, and new fields.
     */
    public MemoryEntry save(String userId, String category, String name,
                            String description, String content,
                            String memoryType, double relevanceScore, String sessionId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO kukuvaia.memories (id, user_id, category, name, description, content,
                                              memory_type, relevance_score, session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, name) DO UPDATE SET
                    category = EXCLUDED.category,
                    description = EXCLUDED.description,
                    content = EXCLUDED.content,
                    memory_type = EXCLUDED.memory_type,
                    relevance_score = EXCLUDED.relevance_score,
                    session_id = EXCLUDED.session_id,
                    updated_at = NOW()
                """, id, userId, category, name, description, content,
                memoryType, relevanceScore, sessionId);
        log.info("Saved memory '{}' for user {} (category={}, type={})", name, userId, category, memoryType);
        return findByUserAndName(userId, name).orElseThrow();
    }

    /**
     * Find all memories for a user, most recently updated first.
     */
    public List<MemoryEntry> findByUser(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.memories WHERE user_id = ? ORDER BY updated_at DESC",
                ROW_MAPPER, userId);
    }

    /**
     * Find memories for a user filtered by category.
     */
    public List<MemoryEntry> findByUserAndCategory(String userId, String category) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.memories WHERE user_id = ? AND category = ? ORDER BY updated_at DESC",
                ROW_MAPPER, userId, category);
    }

    /**
     * Find a specific memory by user and unique name.
     */
    public Optional<MemoryEntry> findByUserAndName(String userId, String name) {
        var results = jdbcTemplate.query(
                "SELECT * FROM kukuvaia.memories WHERE user_id = ? AND name = ?",
                ROW_MAPPER, userId, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * Find memories for a user filtered by memory type.
     */
    public List<MemoryEntry> findByUserAndType(String userId, String memoryType) {
        return jdbcTemplate.query(
                "SELECT * FROM kukuvaia.memories WHERE user_id = ? AND memory_type = ? ORDER BY updated_at DESC",
                ROW_MAPPER, userId, memoryType);
    }

    /**
     * Full-text search across description and content for a user.
     */
    public List<MemoryEntry> search(String userId, String query) {
        return jdbcTemplate.query("""
                SELECT * FROM kukuvaia.memories
                WHERE user_id = ? AND to_tsvector('english', description || ' ' || content) @@ plainto_tsquery('english', ?)
                ORDER BY updated_at DESC LIMIT 20
                """, ROW_MAPPER, userId, query);
    }

    /**
     * Delete a memory by user and name.
     */
    public boolean delete(String userId, String name) {
        int rows = jdbcTemplate.update(
                "DELETE FROM kukuvaia.memories WHERE user_id = ? AND name = ?",
                userId, name);
        if (rows > 0) log.info("Deleted memory '{}' for user {}", name, userId);
        return rows > 0;
    }

    // --- Vector search methods ---

    /**
     * Find top-K similar memories by pgvector cosine distance.
     * Uses schema-qualified cast to avoid search_path issues with the vector type.
     */
    public List<MemoryEntry> findBySimilarity(String userId, float[] vector, int topK) {
        String vectorStr = floatArrayToVectorString(vector);
        return jdbcTemplate.query("""
                SELECT * FROM kukuvaia.memories
                WHERE user_id = ? AND embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """, ROW_MAPPER, userId, vectorStr, topK);
    }

    /**
     * Store embedding vector for a memory.
     * Casts directly to the schema-qualified type to avoid search_path issues.
     */
    public void updateEmbedding(UUID memoryId, float[] vector) {
        String vectorStr = floatArrayToVectorString(vector);
        jdbcTemplate.update(
                "UPDATE kukuvaia.memories SET embedding = ?::vector WHERE id = ?",
                vectorStr, memoryId);
    }

    /**
     * Bump access tracking — called when a memory is retrieved and injected into a prompt.
     * Resets relevance_score to 1.0 (actively used = fully relevant).
     */
    public void touchAccess(UUID memoryId) {
        jdbcTemplate.update("""
                UPDATE kukuvaia.memories
                SET last_accessed_at = NOW(), access_count = access_count + 1, relevance_score = 1.0
                WHERE id = ?
                """, memoryId);
    }

    // --- Consolidation methods ---

    /**
     * Decay relevance for memories not accessed recently.
     */
    public int decayRelevance(double factor, int unaccesedDays) {
        return jdbcTemplate.update("""
                UPDATE kukuvaia.memories
                SET relevance_score = GREATEST(0.1, relevance_score * ?), updated_at = NOW()
                WHERE last_accessed_at < NOW() - CAST(? || ' days' AS INTERVAL)
                  AND relevance_score > 0.1
                """, factor, unaccesedDays);
    }

    /**
     * Find near-duplicate memory pairs (cosine similarity > threshold) for a given user.
     * Returns pairs as [id_a, id_b, name_a, name_b, similarity].
     */
    public List<Map<String, Object>> findNearDuplicates(String userId, double threshold, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT a.id AS id_a, b.id AS id_b,
                       a.name AS name_a, b.name AS name_b,
                       a.relevance_score AS score_a, b.relevance_score AS score_b,
                       1 - (a.embedding <=> b.embedding) AS similarity
                FROM kukuvaia.memories a, kukuvaia.memories b
                WHERE a.user_id = ? AND b.user_id = ?
                  AND a.category = b.category
                  AND a.id < b.id
                  AND a.embedding IS NOT NULL AND b.embedding IS NOT NULL
                  AND 1 - (a.embedding <=> b.embedding) > ?
                ORDER BY similarity DESC
                LIMIT ?
                """, userId, userId, threshold, limit);
    }

    /**
     * Delete a memory by its UUID.
     */
    public void deleteById(UUID id) {
        jdbcTemplate.update("DELETE FROM kukuvaia.memories WHERE id = ?", id);
    }

    /**
     * Delete expired memories.
     */
    public int pruneExpired() {
        return jdbcTemplate.update(
                "DELETE FROM kukuvaia.memories WHERE expires_at IS NOT NULL AND expires_at < NOW()");
    }

    /**
     * Count total memories for a user.
     */
    public int countByUser(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kukuvaia.memories WHERE user_id = ?",
                Integer.class, userId);
        return count != null ? count : 0;
    }

    private static String floatArrayToVectorString(float[] vector) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}

package ai.kukuvaia.memory.repository;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reader/writer for {@code kukuvaia.conversations.summary} — the rolling, in-session
 * narrative summary written by P24 Phase D (LLM-driven long-session summarisation).
 *
 * <p>The {@code summary} column was created as an orphan in the V2 migration; this
 * repository is its first writer. Storage is keyed by Spring AI's
 * {@code chat_memory_conversation_id} — the same identifier
 * {@link SessionRepository}/{@code SPRING_AI_CHAT_MEMORY} use, so resume code reads
 * the persisted summary by the same key under which the session's history is loaded.
 *
 * <p>The summary itself is plain text (Markdown-friendly) written by a cheap LLM —
 * see {@code ConversationSummariser} in kukuvaia-core. Reads must tolerate missing
 * rows: a session that never overflowed context has no row in
 * {@code kukuvaia.conversations}, which is distinct from an existing row with a
 * {@code NULL} summary.
 */
@Component
public class ConversationSummaryRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ConversationSummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the persisted summary for a session, or {@link Optional#empty()} if no row
     * exists or the column is NULL. Treats both cases identically — callers only act on
     * a present, non-blank value.
     */
    public Optional<String> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        var rows = jdbcTemplate.queryForList(
                "SELECT summary FROM kukuvaia.conversations WHERE session_id = ?",
                String.class, sessionId);
        if (rows.isEmpty()) return Optional.empty();
        String summary = rows.getFirst();
        return (summary == null || summary.isBlank()) ? Optional.empty() : Optional.of(summary);
    }

    /**
     * Insert or update the summary for a session. Creates the conversations row if missing
     * — Phase D may run on a session that has had only chat-memory writes so far. The
     * {@code messages} column gets a default {@code '[]'} from the table definition; we
     * don't try to mirror message history here.
     */
    public void upsertSummary(String sessionId, String summary) {
        if (sessionId == null || sessionId.isBlank()) return;
        int updated = jdbcTemplate.update("""
                INSERT INTO kukuvaia.conversations (session_id, summary, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (session_id)
                DO UPDATE SET summary = EXCLUDED.summary, updated_at = NOW()
                """, sessionId, summary);
        log.debug("Upserted conversation summary for session '{}' ({} rows, length={})",
                sessionId, updated, summary == null ? 0 : summary.length());
    }
}

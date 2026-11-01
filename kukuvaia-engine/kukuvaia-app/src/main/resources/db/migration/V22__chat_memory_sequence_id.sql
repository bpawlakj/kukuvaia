-- Spring AI 2.0 JdbcChatMemoryRepository orders messages by a new `sequence_id` column
-- (the message's 0-based position in its conversation) instead of "timestamp".
--
-- The table is created by Spring AI's schema initializer
-- (spring.ai.chat.memory.repository.jdbc.initialize-schema=always) using
-- CREATE TABLE IF NOT EXISTS, so a table created under Spring AI 1.x is never altered.
-- This migration upgrades such a table in place. On a fresh database the table does not
-- exist yet (Spring AI creates it with the 2.0 schema), so the migration is a no-op.
DO $$
BEGIN
    IF to_regclass('spring_ai_chat_memory') IS NULL THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'spring_ai_chat_memory'
          AND column_name = 'sequence_id'
    ) THEN
        ALTER TABLE spring_ai_chat_memory ADD COLUMN sequence_id BIGINT;

        -- Spring AI 1.x read conversations ORDER BY "timestamp"; keep that order.
        -- ctid breaks ties between rows that share a timestamp (approximate insert order).
        UPDATE spring_ai_chat_memory m
        SET sequence_id = ordered.seq
        FROM (
            SELECT ctid,
                   ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY "timestamp", ctid) - 1 AS seq
            FROM spring_ai_chat_memory
        ) ordered
        WHERE m.ctid = ordered.ctid;

        ALTER TABLE spring_ai_chat_memory ALTER COLUMN sequence_id SET NOT NULL;
    END IF;

    CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conversation_id_sequence_id_idx
        ON spring_ai_chat_memory (conversation_id, sequence_id);
END
$$;

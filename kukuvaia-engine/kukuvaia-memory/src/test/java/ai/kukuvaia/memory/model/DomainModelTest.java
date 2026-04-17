package ai.kukuvaia.memory.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates compact constructor constraints on all domain model records.
 */
class DomainModelTest {

    // --- KukuvaiaUser ---

    @Test
    void kukuvaiaUser_validFields_createsRecord() {
        var user = new KukuvaiaUser("u1", "Bartek", Map.of("lang", "pl"), Instant.now(), Instant.now());

        assertThat(user.id()).isEqualTo("u1");
        assertThat(user.displayName()).isEqualTo("Bartek");
        assertThat(user.preferences()).containsEntry("lang", "pl");
    }

    @Test
    void kukuvaiaUser_nullId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new KukuvaiaUser(null, "Bartek", Map.of(), Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void kukuvaiaUser_blankId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new KukuvaiaUser("  ", "Bartek", Map.of(), Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void kukuvaiaUser_nullDisplayName_throwsIllegalArgument() {
        assertThatThrownBy(() -> new KukuvaiaUser("u1", null, Map.of(), Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
    }

    // --- KukuvaiaSession ---

    @Test
    void kukuvaiaSession_validFields_createsRecord() {
        var session = new KukuvaiaSession("s1", "u1", "Session 1", Map.of(), "active", Instant.now(), Instant.now());

        assertThat(session.id()).isEqualTo("s1");
        assertThat(session.userId()).isEqualTo("u1");
        assertThat(session.status()).isEqualTo("active");
    }

    @Test
    void kukuvaiaSession_nullId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new KukuvaiaSession(null, "u1", "s", Map.of(), "active", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void kukuvaiaSession_nullUserId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new KukuvaiaSession("s1", null, "s", Map.of(), "active", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void kukuvaiaSession_nullStatus_throwsIllegalArgument() {
        assertThatThrownBy(() -> new KukuvaiaSession("s1", "u1", "s", Map.of(), null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    // --- ConversationSnapshot ---

    @Test
    void conversationSnapshot_validFields_createsRecord() {
        var snapshot = new ConversationSnapshot("s1", "{}", "Summary", 10, 500, Instant.now());

        assertThat(snapshot.sessionId()).isEqualTo("s1");
        assertThat(snapshot.messageCount()).isEqualTo(10);
        assertThat(snapshot.tokenCount()).isEqualTo(500);
    }

    @Test
    void conversationSnapshot_nullSessionId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new ConversationSnapshot(null, "{}", "Summary", 10, 500, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }

    // --- Plan ---

    @Test
    void plan_validFields_createsRecord() {
        var id = UUID.randomUUID();
        var plan = new Plan(id, "s1", "u1", "Build feature X", "[]", "active", Instant.now(), Instant.now());

        assertThat(plan.id()).isEqualTo(id);
        assertThat(plan.task()).isEqualTo("Build feature X");
        assertThat(plan.status()).isEqualTo("active");
    }

    @Test
    void plan_nullId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new Plan(null, "s1", "u1", "task", "[]", "active", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void plan_nullTask_throwsIllegalArgument() {
        assertThatThrownBy(() -> new Plan(UUID.randomUUID(), "s1", "u1", null, "[]", "active", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
    }

    @Test
    void plan_blankTask_throwsIllegalArgument() {
        assertThatThrownBy(() -> new Plan(UUID.randomUUID(), "s1", "u1", "  ", "[]", "active", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
    }

    @Test
    void plan_nullStatus_throwsIllegalArgument() {
        assertThatThrownBy(() -> new Plan(UUID.randomUUID(), "s1", "u1", "task", "[]", null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
    }

    // --- MemoryEntry ---

    @Test
    void memoryEntry_validFields_createsRecord() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var entry = new MemoryEntry(id, "u1", "project", "key-name", "desc", "content",
                now, now, "explicit", 0.8, 5, now, "s1", now.plusSeconds(3600));

        assertThat(entry.id()).isEqualTo(id);
        assertThat(entry.userId()).isEqualTo("u1");
        assertThat(entry.memoryType()).isEqualTo("explicit");
        assertThat(entry.relevanceScore()).isEqualTo(0.8);
        assertThat(entry.accessCount()).isEqualTo(5);
        assertThat(entry.sessionId()).isEqualTo("s1");
    }

    @Test
    void memoryEntry_nullUserId_throwsIllegalArgument() {
        assertThatThrownBy(() -> new MemoryEntry(UUID.randomUUID(), null, "cat", "name", "desc", "content",
                Instant.now(), Instant.now(), "auto", 0.5, 0, Instant.now(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void memoryEntry_blankName_throwsIllegalArgument() {
        assertThatThrownBy(() -> new MemoryEntry(UUID.randomUUID(), "u1", "cat", "  ", "desc", "content",
                Instant.now(), Instant.now(), "auto", 0.5, 0, Instant.now(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }
}

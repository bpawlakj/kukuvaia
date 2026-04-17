package ai.kukuvaia.provider.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModelRoleRecord — compact constructor validation")
class ModelRoleRecordTest {

    @Test
    @DisplayName("valid record — all fields accepted")
    void valid_allFields_accepted() {
        var record = new ModelRoleRecord(
                UUID.randomUUID(), "advisor", UUID.randomUUID(),
                "Strategic advisor role", Instant.now(), Instant.now());
        assertThat(record.role()).isEqualTo("advisor");
    }

    @Test
    @DisplayName("null role — throws IllegalArgumentException")
    void nullRole_throws() {
        assertThatThrownBy(() -> new ModelRoleRecord(
                UUID.randomUUID(), null, UUID.randomUUID(),
                null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    @Test
    @DisplayName("blank role — throws IllegalArgumentException")
    void blankRole_throws() {
        assertThatThrownBy(() -> new ModelRoleRecord(
                UUID.randomUUID(), "  ", UUID.randomUUID(),
                null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null modelId — throws IllegalArgumentException")
    void nullModelId_throws() {
        assertThatThrownBy(() -> new ModelRoleRecord(
                UUID.randomUUID(), "advisor", null,
                null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId");
    }

    @Test
    @DisplayName("null description — accepted (optional field)")
    void nullDescription_accepted() {
        var record = new ModelRoleRecord(
                UUID.randomUUID(), "advisor", UUID.randomUUID(),
                null, Instant.now(), Instant.now());
        assertThat(record.description()).isNull();
    }
}

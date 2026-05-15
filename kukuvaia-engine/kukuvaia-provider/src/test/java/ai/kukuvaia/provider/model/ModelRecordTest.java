package ai.kukuvaia.provider.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModelRecord — compact constructor validation")
class ModelRecordTest {

    @Test
    @DisplayName("valid record — all fields accepted")
    void valid_allFields_accepted() {
        var record = new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), "haiku",
                "Claude Haiku 4.5", List.of("text", "code"), "economy",
                4096, 200000, true, Map.of(), null,
                Instant.now(), Instant.now());
        assertThat(record.modelId()).isEqualTo("haiku");
        assertThat(record.capabilities()).containsExactly("text", "code");
    }

    @Test
    @DisplayName("null providerId — throws IllegalArgumentException")
    void nullProviderId_throws() {
        assertThatThrownBy(() -> new ModelRecord(
                UUID.randomUUID(), null, "haiku",
                null, List.of(), "standard",
                4096, null, true, Map.of(), null,
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerId");
    }

    @Test
    @DisplayName("null modelId — throws IllegalArgumentException")
    void nullModelId_throws() {
        assertThatThrownBy(() -> new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), null,
                null, List.of(), "standard",
                4096, null, true, Map.of(), null,
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId");
    }

    @Test
    @DisplayName("blank modelId — throws IllegalArgumentException")
    void blankModelId_throws() {
        assertThatThrownBy(() -> new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), "  ",
                null, List.of(), "standard",
                4096, null, true, Map.of(), null,
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null capabilities — defaults to empty list")
    void nullCapabilities_defaultsToEmptyList() {
        var record = new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), "haiku",
                null, null, "standard",
                4096, null, true, Map.of(), null,
                Instant.now(), Instant.now());
        assertThat(record.capabilities()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("null tier — defaults to standard")
    void nullTier_defaultsToStandard() {
        var record = new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), "haiku",
                null, List.of(), null,
                4096, null, true, Map.of(), null,
                Instant.now(), Instant.now());
        assertThat(record.tier()).isEqualTo("standard");
    }

    @Test
    @DisplayName("zero maxTokens — defaults to 4096")
    void zeroMaxTokens_defaultsTo4096() {
        var record = new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), "haiku",
                null, List.of(), "standard",
                0, null, true, Map.of(), null,
                Instant.now(), Instant.now());
        assertThat(record.maxTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("negative maxTokens — defaults to 4096")
    void negativeMaxTokens_defaultsTo4096() {
        var record = new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), "haiku",
                null, List.of(), "standard",
                -1, null, true, Map.of(), null,
                Instant.now(), Instant.now());
        assertThat(record.maxTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("null contextWindow — accepted as nullable")
    void nullContextWindow_accepted() {
        var record = new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), "haiku",
                null, List.of(), "standard",
                4096, null, true, Map.of(), null,
                Instant.now(), Instant.now());
        assertThat(record.contextWindow()).isNull();
    }
}

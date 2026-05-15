package ai.kukuvaia.provider.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProviderRecord — compact constructor validation")
class ProviderRecordTest {

    @Test
    @DisplayName("valid record — all fields accepted")
    void valid_allFields_accepted() {
        var record = new ProviderRecord(
                UUID.randomUUID(), "smartgate", "smartgate",
                "https://llm.example.com", "SMARTGATE_API_KEY",
                true, 0, Map.of("timeout", 120),
                Instant.now(), Instant.now());
        assertThat(record.name()).isEqualTo("smartgate");
    }

    @Test
    @DisplayName("null name — throws IllegalArgumentException")
    void nullName_throws() {
        assertThatThrownBy(() -> new ProviderRecord(
                UUID.randomUUID(), null, "smartgate",
                "https://example.com", "KEY", true, 0, Map.of(),
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("blank name — throws IllegalArgumentException")
    void blankName_throws() {
        assertThatThrownBy(() -> new ProviderRecord(
                UUID.randomUUID(), "  ", "smartgate",
                "https://example.com", "KEY", true, 0, Map.of(),
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null type — throws IllegalArgumentException")
    void nullType_throws() {
        assertThatThrownBy(() -> new ProviderRecord(
                UUID.randomUUID(), "test", null,
                "https://example.com", "KEY", true, 0, Map.of(),
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("null baseUrl — throws IllegalArgumentException")
    void nullBaseUrl_throws() {
        assertThatThrownBy(() -> new ProviderRecord(
                UUID.randomUUID(), "test", "smartgate",
                null, "KEY", true, 0, Map.of(),
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    @DisplayName("null apiKeyRef — throws IllegalArgumentException")
    void nullApiKeyRef_throws() {
        assertThatThrownBy(() -> new ProviderRecord(
                UUID.randomUUID(), "test", "smartgate",
                "https://example.com", null, true, 0, Map.of(),
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKeyRef");
    }

    @Test
    @DisplayName("null config — defaults to empty map")
    void nullConfig_defaultsToEmptyMap() {
        var record = new ProviderRecord(
                UUID.randomUUID(), "test", "smartgate",
                "https://example.com", "KEY", true, 0, null,
                Instant.now(), Instant.now());
        assertThat(record.config()).isNotNull().isEmpty();
    }
}

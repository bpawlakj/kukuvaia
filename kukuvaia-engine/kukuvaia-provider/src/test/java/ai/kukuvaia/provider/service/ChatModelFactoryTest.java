package ai.kukuvaia.provider.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatModelFactory — provider config helpers")
class ChatModelFactoryTest {

    @Test
    @DisplayName("resolveCompletionsPath — config absent — returns null (caller keeps Spring AI default)")
    void resolveCompletionsPath_missing_returnsNull() {
        assertThat(ChatModelFactory.resolveCompletionsPath(Map.of())).isNull();
        assertThat(ChatModelFactory.resolveCompletionsPath(null)).isNull();
    }

    @Test
    @DisplayName("resolveCompletionsPath — Copilot-style override — returns the configured path")
    void resolveCompletionsPath_copilotOverride_returnsPath() {
        Map<String, Object> config = Map.of("completions-path", "/chat/completions");

        assertThat(ChatModelFactory.resolveCompletionsPath(config)).isEqualTo("/chat/completions");
    }

    @Test
    @DisplayName("resolveCompletionsPath — blank value — treated as absent")
    void resolveCompletionsPath_blank_returnsNull() {
        Map<String, Object> config = Map.of("completions-path", "   ");

        assertThat(ChatModelFactory.resolveCompletionsPath(config)).isNull();
    }

    @Test
    @DisplayName("resolveExtraHeaders — no headers key — returns empty map")
    void resolveExtraHeaders_missing_returnsEmpty() {
        assertThat(ChatModelFactory.resolveExtraHeaders(Map.of())).isEmpty();
        assertThat(ChatModelFactory.resolveExtraHeaders(null)).isEmpty();
    }

    @Test
    @DisplayName("resolveExtraHeaders — Copilot integration headers — returns them in declared order")
    void resolveExtraHeaders_copilotHeaders_returnsAll() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Copilot-Integration-Id", "kukuvaia");
        headers.put("Editor-Version", "kukuvaia/0.1");
        Map<String, Object> config = Map.of("headers", headers);

        Map<String, String> result = ChatModelFactory.resolveExtraHeaders(config);

        assertThat(result)
                .containsEntry("Copilot-Integration-Id", "kukuvaia")
                .containsEntry("Editor-Version", "kukuvaia/0.1");
        assertThat(result.keySet()).containsExactly("Copilot-Integration-Id", "Editor-Version");
    }

    @Test
    @DisplayName("resolveExtraHeaders — null/blank entries — silently skipped")
    void resolveExtraHeaders_nullEntries_skipped() {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-Valid", "ok");
        headers.put("", "blank-key");
        headers.put("X-Null", null);
        Map<String, Object> config = Map.of("headers", headers);

        Map<String, String> result = ChatModelFactory.resolveExtraHeaders(config);

        assertThat(result).containsExactly(Map.entry("X-Valid", "ok"));
    }
}

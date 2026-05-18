package ai.kukuvaia.advisors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolCompactionRegistry — per-tool summary lookup")
class ToolCompactionRegistryTest {

    @Test
    @DisplayName("register and get — round-trip works")
    void register_andGet_roundTrip() {
        var registry = new ToolCompactionRegistry();
        ToolCompactSummary fn = (args, data) -> "summary of " + data.length() + " chars";

        registry.register("my_tool", fn);

        assertThat(registry.has("my_tool")).isTrue();
        assertThat(registry.get("my_tool")).contains(fn);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("get for unregistered tool — returns empty")
    void get_unregistered_returnsEmpty() {
        var registry = new ToolCompactionRegistry();
        assertThat(registry.get("missing")).isEmpty();
        assertThat(registry.has("missing")).isFalse();
    }

    @Test
    @DisplayName("register with blank/null inputs — silently ignored")
    void register_blankInputs_ignored() {
        var registry = new ToolCompactionRegistry();
        registry.register(null, (a, d) -> "x");
        registry.register("", (a, d) -> "x");
        registry.register("ok", null);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("re-register — replaces existing summary")
    void register_replace_overwrites() {
        var registry = new ToolCompactionRegistry();
        registry.register("t", (a, d) -> "first");
        registry.register("t", (a, d) -> "second");

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("t").orElseThrow().summarise("", "")).isEqualTo("second");
    }

    @Test
    @DisplayName("pin and isPinned — round-trip works, idempotent on repeat")
    void pin_roundTrip() {
        var registry = new ToolCompactionRegistry();
        registry.pin("sticky");
        registry.pin("sticky");   // idempotent

        assertThat(registry.isPinned("sticky")).isTrue();
        assertThat(registry.pinnedCount()).isEqualTo(1);
        assertThat(registry.isPinned("other")).isFalse();
    }

    @Test
    @DisplayName("unpin removes the pin")
    void unpin_removes() {
        var registry = new ToolCompactionRegistry();
        registry.pin("x");
        assertThat(registry.isPinned("x")).isTrue();

        registry.unpin("x");
        assertThat(registry.isPinned("x")).isFalse();
        assertThat(registry.pinnedCount()).isZero();
    }

    @Test
    @DisplayName("pin with blank/null inputs — ignored")
    void pin_blankInputs_ignored() {
        var registry = new ToolCompactionRegistry();
        registry.pin(null);
        registry.pin("");
        assertThat(registry.pinnedCount()).isZero();
    }
}

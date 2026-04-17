package ai.kukuvaia.extensions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ToolSpec — YAML+Lua tool definition")
class ToolSpecTest {

    private static final List<Map<String, Object>> SIMPLE_STEPS = List.of(
            Map.of("return", "ok"));

    @Test
    @DisplayName("valid tool — creates successfully")
    void constructor_valid_creates() {
        var spec = new ToolSpec("read_file", "Read a file", true, true,
                Map.of("path", new ToolSpec.ParameterSpec("string", "File path", true)),
                SIMPLE_STEPS, null);

        assertThat(spec.name()).isEqualTo("read_file");
        assertThat(spec.readOnly()).isTrue();
        assertThat(spec.parameters()).hasSize(1);
    }

    @Test
    @DisplayName("null name — throws")
    void constructor_nullName_throws() {
        assertThatThrownBy(() -> new ToolSpec(null, "desc", true, true, null, SIMPLE_STEPS, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty steps — throws")
    void constructor_emptySteps_throws() {
        assertThatThrownBy(() -> new ToolSpec("test", "desc", true, true, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null parameters — defaults to empty map")
    void constructor_nullParams_defaultsToEmpty() {
        var spec = new ToolSpec("test", "desc", true, true, null, SIMPLE_STEPS, null);
        assertThat(spec.parameters()).isEmpty();
    }
}

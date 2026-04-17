package ai.kukuvaia.extensions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ScriptToolCallbackProvider — YAML+Lua tools as ToolCallbacks")
class ScriptToolCallbackProviderTest {

    @TempDir
    Path tempDir;
    private ScriptToolCallbackProvider provider;

    @BeforeEach
    void setUp() {
        var toolLoader = mock(ToolLoader.class);
        when(toolLoader.loadTools()).thenReturn(List.of(
                new ToolSpec("greet", "Say hello", true, true,
                        Map.of("name", new ToolSpec.ParameterSpec("string", "Name", true)),
                        List.of(Map.of("lua", "return 'Hello ' .. name"),
                                Map.of("return", "${result}")),
                        Map.of())
        ));
        provider = new ScriptToolCallbackProvider(toolLoader, mock(JdbcTemplate.class),
                tempDir.toString());
    }

    @Test
    @DisplayName("getToolCallbacks — returns callbacks for each tool")
    void getToolCallbacks_returnsCallbacks() {
        var callbacks = provider.getToolCallbacks();

        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("greet");
    }

    @Test
    @DisplayName("tool definition — has JSON schema from parameters")
    void getToolDefinition_hasSchema() {
        var callbacks = provider.getToolCallbacks();
        var def = callbacks[0].getToolDefinition();

        assertThat(def.inputSchema()).contains("\"name\"");
        assertThat(def.inputSchema()).contains("\"required\"");
    }
}

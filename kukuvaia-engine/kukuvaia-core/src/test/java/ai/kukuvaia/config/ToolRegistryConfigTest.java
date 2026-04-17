package ai.kukuvaia.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ToolRegistryConfig — tool resolution by name")
class ToolRegistryConfigTest {

    private ToolRegistryConfig registry;

    @BeforeEach
    void setUp() {
        var def1 = mock(ToolDefinition.class);
        when(def1.name()).thenReturn("tool_alpha");
        var callback1 = mock(ToolCallback.class);
        when(callback1.getToolDefinition()).thenReturn(def1);

        var def2 = mock(ToolDefinition.class);
        when(def2.name()).thenReturn("tool_beta");
        var callback2 = mock(ToolCallback.class);
        when(callback2.getToolDefinition()).thenReturn(def2);

        var provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{callback1, callback2});

        registry = new ToolRegistryConfig(List.of(provider));
    }

    @Test
    @DisplayName("resolve — returns tool by exact name")
    void resolve_existingTool_returnsPresent() {
        assertThat(registry.resolve("tool_alpha")).isPresent();
    }

    @Test
    @DisplayName("resolve — returns empty for unknown tool")
    void resolve_unknownTool_returnsEmpty() {
        assertThat(registry.resolve("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("resolveAll — returns only found tools")
    void resolveAll_mixedNames_returnsOnlyFound() {
        var tools = registry.resolveAll(List.of("tool_alpha", "nonexistent", "tool_beta"));

        assertThat(tools).hasSize(2);
    }

    @Test
    @DisplayName("toolNames — returns all registered names")
    void toolNames_afterInit_containsAll() {
        assertThat(registry.toolNames()).containsExactlyInAnyOrder("tool_alpha", "tool_beta");
    }
}

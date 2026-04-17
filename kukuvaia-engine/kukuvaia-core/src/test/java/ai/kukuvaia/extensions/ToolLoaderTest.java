package ai.kukuvaia.extensions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ToolLoader — TOOL.md parsing (YAML+Lua format)")
class ToolLoaderTest {

    @TempDir
    Path tempDir;
    private ToolLoader loader;

    @BeforeEach
    void setUp() {
        var extensionLoader = mock(ExtensionLoader.class);
        when(extensionLoader.getExtensionRoot()).thenReturn(Optional.of(tempDir));
        loader = new ToolLoader(extensionLoader);
    }

    @Test
    @DisplayName("YAML steps tool — parses correctly")
    void loadTools_yamlSteps_parsesCorrectly() throws IOException {
        Path toolDir = tempDir.resolve("tools/find-files");
        Files.createDirectories(toolDir);
        Files.writeString(toolDir.resolve("TOOL.md"), """
                ---
                name: find_files
                description: Find files by glob
                readOnly: true
                parameters:
                  pattern:
                    type: string
                    description: Glob pattern
                    required: true
                ---
                - fs.find: {glob: "${pattern}"}
                  as: files
                - return: "${files}"
                """);

        loader.reload();
        var tools = loader.loadTools();

        assertThat(tools).hasSize(1);
        var tool = tools.getFirst();
        assertThat(tool.name()).isEqualTo("find_files");
        assertThat(tool.readOnly()).isTrue();
        assertThat(tool.steps()).hasSize(2);
        assertThat(tool.parameters()).containsKey("pattern");
    }

    @Test
    @DisplayName("no steps — skipped")
    void loadTools_noSteps_skipped() throws IOException {
        Path toolDir = tempDir.resolve("tools/empty");
        Files.createDirectories(toolDir);
        Files.writeString(toolDir.resolve("TOOL.md"), """
                ---
                name: empty
                description: No steps
                ---
                """);

        loader.reload();
        assertThat(loader.loadTools()).isEmpty();
    }

    @Test
    @DisplayName("no tools directory — returns empty")
    void loadTools_noDir_returnsEmpty() {
        assertThat(loader.loadTools()).isEmpty();
    }

    @Test
    @DisplayName("multiple tools — loads all")
    void loadTools_multiple_loadsAll() throws IOException {
        for (String name : java.util.List.of("tool-a", "tool-b")) {
            Path toolDir = tempDir.resolve("tools/" + name);
            Files.createDirectories(toolDir);
            Files.writeString(toolDir.resolve("TOOL.md"), """
                    ---
                    name: %s
                    description: Tool %s
                    ---
                    - return: "%s"
                    """.formatted(name, name, name));
        }

        loader.reload();
        assertThat(loader.loadTools()).hasSize(2);
    }
}

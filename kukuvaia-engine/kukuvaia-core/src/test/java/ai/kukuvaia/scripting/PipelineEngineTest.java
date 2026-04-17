package ai.kukuvaia.scripting;

import ai.kukuvaia.extensions.ToolBridgeAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("PipelineEngine — YAML+Lua pipeline execution")
class PipelineEngineTest {

    @TempDir
    Path tempDir;
    private PipelineEngine engine;

    @BeforeEach
    void setUp() {
        var bridge = new ToolBridgeAPI(tempDir, mock(JdbcTemplate.class));
        engine = new PipelineEngine(bridge);
    }

    @Test
    @DisplayName("simple return step — returns interpolated value")
    void execute_returnStep_returnsValue() {
        var steps = List.<Map<String, Object>>of(Map.of("return", "hello world"));
        assertThat(engine.execute(steps, Map.of())).isEqualTo("hello world");
    }

    @Test
    @DisplayName("variable interpolation — substitutes args")
    void execute_interpolation_substitutesArgs() {
        var steps = List.<Map<String, Object>>of(Map.of("return", "Hi ${name}!"));
        assertThat(engine.execute(steps, Map.of("name", "Bob"))).isEqualTo("Hi Bob!");
    }

    @Test
    @DisplayName("fs.write + fs.read — round-trip")
    void execute_fsWriteRead_roundTrip() throws IOException {
        var steps = List.<Map<String, Object>>of(
                Map.of("fs.write", Map.of("path", "test.md", "content", "Hello"), "as", "w"),
                Map.of("fs.read", Map.of("path", "test.md"), "as", "content"),
                Map.of("return", "${content}")
        );
        String result = engine.execute(steps, Map.of());
        assertThat(result).isEqualTo("Hello");
    }

    @Test
    @DisplayName("fs.find — finds files")
    void execute_fsFind_findsFiles() throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "doc a");
        Files.writeString(tempDir.resolve("b.txt"), "doc b");

        var steps = List.<Map<String, Object>>of(
                Map.of("fs.find", Map.of("glob", "*.md"), "as", "files"),
                Map.of("return", "${files}")
        );
        String result = engine.execute(steps, Map.of());
        assertThat(result).contains("a.md");
        assertThat(result).doesNotContain("b.txt");
    }

    @Test
    @DisplayName("lua step — executes Lua and stores result")
    void execute_luaStep_executesLua() {
        var steps = List.<Map<String, Object>>of(
                Map.of("lua", "return string.upper('hello')", "as", "upper"),
                Map.of("return", "${upper}")
        );
        assertThat(engine.execute(steps, Map.of())).isEqualTo("HELLO");
    }

    @Test
    @DisplayName("lua with variables — accesses pipeline variables")
    void execute_luaWithVars_accessesVars() {
        var steps = List.<Map<String, Object>>of(
                Map.of("lua", "return name .. ' is ' .. tostring(age)", "as", "msg"),
                Map.of("return", "${msg}")
        );
        String result = engine.execute(steps, Map.of("name", "Bob", "age", 30));
        assertThat(result).startsWith("Bob is 30");
    }

    @Test
    @DisplayName("lua with fs bridge — calls filesystem")
    void execute_luaWithFsBridge_callsFs() throws IOException {
        Files.writeString(tempDir.resolve("data.txt"), "content123");

        var steps = List.<Map<String, Object>>of(
                Map.of("lua", "return fs.read('data.txt')", "as", "data"),
                Map.of("return", "${data}")
        );
        assertThat(engine.execute(steps, Map.of())).isEqualTo("content123");
    }

    @Test
    @DisplayName("multi-step pipeline — chains results")
    void execute_multiStep_chainsResults() throws IOException {
        Files.writeString(tempDir.resolve("hello.md"), "Hello World");

        var steps = List.<Map<String, Object>>of(
                Map.of("fs.read", Map.of("path", "hello.md"), "as", "content"),
                Map.of("lua", "return string.upper(content)", "as", "upper"),
                Map.of("return", "${upper}")
        );
        assertThat(engine.execute(steps, Map.of())).isEqualTo("HELLO WORLD");
    }
}

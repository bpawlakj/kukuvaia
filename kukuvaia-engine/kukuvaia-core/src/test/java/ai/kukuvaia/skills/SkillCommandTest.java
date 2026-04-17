package ai.kukuvaia.skills;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TableBlock;
import ai.kukuvaia.output.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SkillCommand — /skill slash command")
class SkillCommandTest {

    private SkillCommand command;
    private SkillRegistry registry;
    private SkillExecutor executor;

    @BeforeEach
    void setUp() {
        registry = mock(SkillRegistry.class);
        executor = mock(SkillExecutor.class);
        command = new SkillCommand(registry, executor);
    }

    @Test
    @DisplayName("no args — lists all skills as table")
    void execute_noArgs_listsSkills() {
        var spec = new SkillSpec("hello", "Say hello", "/hello",
                SkillSpec.SkillType.SCRIPT, "javascript", 5000, List.of(),
                "body", "function execute(ctx) { return 'hi'; }");
        when(registry.allSkills()).thenReturn(Map.of("hello", spec));

        var result = command.execute("", "session-1");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).isInstanceOf(TableBlock.class);
    }

    @Test
    @DisplayName("valid skill name — dispatches to executor")
    void execute_validSkill_dispatches() {
        var spec = new SkillSpec("hello", "Say hello", null,
                SkillSpec.SkillType.SCRIPT, "javascript", 5000, List.of(),
                "body", "function execute(ctx) { return 'hi'; }");
        when(registry.resolve("hello")).thenReturn(Optional.of(spec));
        when(executor.execute(eq(spec), eq("world"), eq("session-1")))
                .thenReturn(List.of(new TextBlock("hi world", null)));

        var result = command.execute("hello world", "session-1");

        assertThat(result).hasSize(1);
        assertThat(((TextBlock) result.getFirst()).content()).isEqualTo("hi world");
    }

    @Test
    @DisplayName("unknown skill — returns error")
    void execute_unknownSkill_returnsError() {
        when(registry.resolve("nonexistent")).thenReturn(Optional.empty());

        var result = command.execute("nonexistent", "session-1");

        assertThat(result).hasSize(1);
        assertThat(((TextBlock) result.getFirst()).style()).isEqualTo("error");
    }
}

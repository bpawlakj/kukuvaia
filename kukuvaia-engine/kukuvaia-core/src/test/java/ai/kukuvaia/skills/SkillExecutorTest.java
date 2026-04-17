package ai.kukuvaia.skills;

import ai.kukuvaia.agent.AgentService;
import ai.kukuvaia.output.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("SkillExecutor — dispatches prompt and Lua script skills")
class SkillExecutorTest {

    private SkillExecutor executor;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        executor = new SkillExecutor(agentService);
    }

    @Test
    @DisplayName("prompt skill — delegates to AgentService.chat()")
    void execute_promptSkill_delegatesToAgent() {
        var skill = new SkillSpec("summarize", "Summarize", null,
                SkillSpec.SkillType.PROMPT, null, 5000, List.of(),
                "Read and summarize the document.", null);
        when(agentService.chat(eq("s1"), anyString())).thenReturn("Summary done");

        var result = executor.execute(skill, "doc.md", "s1");

        assertThat(result).hasSize(1);
        assertThat(((TextBlock) result.getFirst()).content()).isEqualTo("Summary done");
        verify(agentService).chat(eq("s1"), anyString());
    }

    @Test
    @DisplayName("script skill — executes Lua and returns result")
    void execute_scriptSkill_executesLua() {
        var skill = new SkillSpec("hello", "Hello", null,
                SkillSpec.SkillType.SCRIPT, "lua", 5000, List.of(),
                "body", "return 'hello ' .. args");

        var result = executor.execute(skill, "world", "s1");

        assertThat(result).hasSize(1);
        assertThat(((TextBlock) result.getFirst()).content()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("script skill error — returns error TextBlock")
    void execute_scriptError_returnsError() {
        var skill = new SkillSpec("broken", "Broken", null,
                SkillSpec.SkillType.SCRIPT, "lua", 5000, List.of(),
                "body", "error('boom')");

        var result = executor.execute(skill, "", "s1");

        assertThat(((TextBlock) result.getFirst()).style()).isEqualTo("error");
    }

    @Test
    @DisplayName("prompt skill — injects skill body into message")
    void execute_promptSkill_includesBody() {
        var skill = new SkillSpec("review", "Review", null,
                SkillSpec.SkillType.PROMPT, null, 5000, List.of(),
                "Check for security issues.", null);
        when(agentService.chat(anyString(), anyString())).thenReturn("ok");

        executor.execute(skill, "file.java", "s1");

        verify(agentService).chat(eq("s1"),
                org.mockito.ArgumentMatchers.contains("Check for security issues"));
    }
}

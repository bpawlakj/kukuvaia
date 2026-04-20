package ai.kukuvaia.agent;

import ai.kukuvaia.commands.CommandRegistry;
import ai.kukuvaia.commands.SlashCommand;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.skills.SkillExecutor;
import ai.kukuvaia.skills.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("CommandRouter — routes slash commands vs free text")
class CommandRouterTest {

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        SlashCommand helpCmd = new SlashCommand() {
            @Override public String name() { return "help"; }
            @Override public String description() { return "Help"; }
            @Override public List<OutputBlock> execute(String args, String sessionId) {
                return List.of(new TextBlock("help output", null));
            }
        };
        var registry = new CommandRegistry(List.of(helpCmd));
        var agentService = mock(AgentService.class);
        var skillRegistry = mock(SkillRegistry.class);
        var skillExecutor = mock(SkillExecutor.class);
        var planningModeService = mock(PlanningModeService.class);
        router = new CommandRouter(registry, agentService, skillRegistry, skillExecutor, planningModeService);
    }

    @Test
    @DisplayName("route — dispatches slash command to registry")
    void route_slashCommand_dispatchesToRegistry() {
        var result = router.route("/help", "session-1").collectList().block();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) result.getFirst()).content()).isEqualTo("help output");
    }

    @Test
    @DisplayName("route — unknown slash command returns error")
    void route_unknownCommand_returnsError() {
        var result = router.route("/nonexistent", "session-1").collectList().block();

        assertThat(result).hasSize(1);
        assertThat(((TextBlock) result.getFirst()).content()).contains("Unknown command");
    }

    @Test
    @DisplayName("route — empty input returns error")
    void route_emptyInput_returnsError() {
        var result = router.route("", "session-1").collectList().block();

        assertThat(result).hasSize(1);
        assertThat(((TextBlock) result.getFirst()).style()).isEqualTo("error");
    }
}

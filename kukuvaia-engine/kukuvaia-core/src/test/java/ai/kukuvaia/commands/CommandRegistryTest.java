package ai.kukuvaia.commands;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommandRegistry — slash command dispatch")
class CommandRegistryTest {

    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        SlashCommand testCmd = new SlashCommand() {
            @Override public String name() { return "test"; }
            @Override public String description() { return "Test command"; }
            @Override public List<OutputBlock> execute(String args, String sessionId) {
                return List.of(new TextBlock("executed:" + args, null));
            }
        };
        registry = new CommandRegistry(List.of(testCmd));
    }

    @Test
    @DisplayName("resolve — finds registered command by name")
    void resolve_existingCommand_returnsPresent() {
        assertThat(registry.resolve("test")).isPresent();
    }

    @Test
    @DisplayName("resolve — returns empty for unknown command")
    void resolve_unknownCommand_returnsEmpty() {
        assertThat(registry.resolve("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("allCommands — lists all registered commands")
    void allCommands_afterInit_containsRegistered() {
        assertThat(registry.allCommands()).containsKey("test");
    }
}

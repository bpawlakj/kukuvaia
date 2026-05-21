package ai.kukuvaia.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.agent.PersonaSpec;
import ai.kukuvaia.config.ToolRegistryConfig;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.skills.SkillRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HelpCommand — persona-aware tool listing")
class HelpCommandTest {

    private ApplicationContext appContext;
    private CommandRegistry commandRegistry;
    private ToolRegistryConfig toolRegistry;
    private SkillRegistry skillRegistry;
    private PersonaService personaService;
    private HelpCommand helpCommand;

    @BeforeEach
    void setUp() {
        appContext = mock(ApplicationContext.class);
        commandRegistry = mock(CommandRegistry.class);
        toolRegistry = mock(ToolRegistryConfig.class);
        skillRegistry = mock(SkillRegistry.class);
        personaService = mock(PersonaService.class);

        when(appContext.getBean(CommandRegistry.class)).thenReturn(commandRegistry);
        when(commandRegistry.allCommands()).thenReturn(Map.of()); // empty commands table is fine
        when(skillRegistry.allSkills()).thenReturn(Map.of());

        // Registry contains BOTH a persona-specific MCP tool AND a general internal tool.
        when(toolRegistry.toolNames()).thenReturn(
                List.of("tool_persona_alpha", "tool_persona_beta", "startPlanning", "list_plans"));
        // Named personas (test-persona-2, test-persona) collectively whitelist the MCP tools below.
        when(personaService.namedPersonaToolUnion()).thenReturn(
                Set.of("tool_persona_alpha", "tool_persona_beta"));

        helpCommand = new HelpCommand(appContext, toolRegistry, skillRegistry, personaService);
    }

    @Test
    @DisplayName("default persona (assistant) — only General tools, no Persona section")
    void defaultPersona_onlyGeneralTools() {
        when(personaService.getActivePersona("s1"))
                .thenReturn(new PersonaSpec("assistant", "default", "prompt", List.of()));

        List<OutputBlock> blocks = helpCommand.execute("", "s1");

        var textBlocks = blocks.stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> (TextBlock) b)
                .toList();

        assertThat(textBlocks)
                .extracting(TextBlock::content)
                .noneMatch(c -> c.startsWith("Persona tools"));

        // General tools = registry minus union = startPlanning, list_plans
        assertThat(textBlocks)
                .anyMatch(tb -> tb.content().startsWith("General tools:")
                        && tb.content().contains("startPlanning")
                        && tb.content().contains("list_plans")
                        && !tb.content().contains("tool_persona_alpha")
                        && !tb.content().contains("tool_persona_beta"));
    }

    @Test
    @DisplayName("named persona (test-persona) — Persona tools section + General tools section")
    void namedPersona_personaPlusGeneral() {
        when(personaService.getActivePersona("s1"))
                .thenReturn(new PersonaSpec(
                        "test-persona", "trusted operator", "prompt",
                        List.of("tool_persona_alpha", "tool_persona_beta", "tool_persona_gamma"))); // tool_persona_gamma unregistered

        List<OutputBlock> blocks = helpCommand.execute("", "s1");

        var textBlocks = blocks.stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> (TextBlock) b)
                .toList();

        // Persona tools — includes ONLY tools that are also in the registry (tool_persona_gamma filtered out)
        assertThat(textBlocks)
                .anyMatch(tb -> tb.content().startsWith("Persona tools (test-persona):")
                        && tb.content().contains("tool_persona_alpha")
                        && tb.content().contains("tool_persona_beta")
                        && !tb.content().contains("tool_persona_gamma"));

        // General tools still present, still excludes the persona's MCP tools
        assertThat(textBlocks)
                .anyMatch(tb -> tb.content().startsWith("General tools:")
                        && tb.content().contains("startPlanning")
                        && tb.content().contains("list_plans"));
    }

    @Test
    @DisplayName("empty registry — no tool blocks emitted (no empty sections)")
    void emptyRegistry_noToolBlocks() {
        when(toolRegistry.toolNames()).thenReturn(List.of());
        when(personaService.getActivePersona("s1"))
                .thenReturn(new PersonaSpec("assistant", "default", "prompt", List.of()));

        List<OutputBlock> blocks = helpCommand.execute("", "s1");

        assertThat(blocks).noneMatch(b -> b instanceof TextBlock
                && (((TextBlock) b).content().contains("tools:")));
    }
}

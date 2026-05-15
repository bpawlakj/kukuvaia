package ai.kukuvaia.commands;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.agent.PersonaSpec;
import ai.kukuvaia.config.ToolRegistryConfig;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TableBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.skills.SkillRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * /help — persona-aware listing of commands, tools, and skills.
 *
 * <p>Tools are split into TWO sections:
 * <ul>
 *   <li><b>Persona tools</b> — registered tools that fall under the active persona's
 *       {@code toolFilter}. Shown only when a non-default persona is active. These are typically
 *       MCP tools mounted by that persona (e.g. {@code create_rule}, {@code find_outline_templates}
 *       under {@code rule-editor}).</li>
 *   <li><b>General tools</b> — registered tools that are NOT in any named persona's
 *       {@code toolFilter}. These are always reachable regardless of persona — typically internal
 *       kukuvaia tools (planning, sessions, etc.).</li>
 * </ul>
 *
 * <p>Default persona ({@code assistant}) shows General tools only. Named persona shows Persona
 * tools first, then General tools, so the operator immediately sees what was unlocked by switching.
 *
 * <p>Uses ApplicationContext for lazy CommandRegistry lookup to break circular dependency.
 */
@Component
public class HelpCommand implements SlashCommand {

    private static final String DEFAULT_PERSONA = "assistant";

    private final ApplicationContext applicationContext;
    private final ToolRegistryConfig toolRegistry;
    private final SkillRegistry skillRegistry;
    private final PersonaService personaService;

    public HelpCommand(ApplicationContext applicationContext, ToolRegistryConfig toolRegistry,
                       SkillRegistry skillRegistry, PersonaService personaService) {
        this.applicationContext = applicationContext;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.personaService = personaService;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available commands and tools";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        List<OutputBlock> blocks = new ArrayList<>();

        // Commands table (lazy lookup to avoid circular dependency)
        var commandRegistry = applicationContext.getBean(CommandRegistry.class);
        List<List<String>> commandRows = commandRegistry.allCommands().values().stream()
                .map(cmd -> List.of("/" + cmd.name(), cmd.description()))
                .toList();
        blocks.add(new TableBlock("Commands", List.of("Command", "Description"), commandRows));

        // Tools — persona-aware split
        Set<String> registered = new LinkedHashSet<>(toolRegistry.toolNames());
        if (!registered.isEmpty()) {
            PersonaSpec persona = personaService.getActivePersona(sessionId);
            Set<String> namedUnion = personaService.namedPersonaToolUnion();

            boolean isNamedPersona = !DEFAULT_PERSONA.equals(persona.name());

            if (isNamedPersona) {
                Set<String> personaTools = new LinkedHashSet<>(persona.toolFilter());
                personaTools.retainAll(registered); // only show tools that ACTUALLY exist in registry
                if (!personaTools.isEmpty()) {
                    blocks.add(new TextBlock(
                            "Persona tools (" + persona.name() + "): " + String.join(", ", personaTools),
                            null));
                }
            }

            // General tools: registered tools NOT belonging to any named persona's whitelist
            Set<String> general = new LinkedHashSet<>(registered);
            general.removeAll(namedUnion);
            if (!general.isEmpty()) {
                blocks.add(new TextBlock("General tools: " + String.join(", ", general), null));
            }
        }

        // Skills table
        var allSkills = skillRegistry.allSkills();
        if (!allSkills.isEmpty()) {
            List<List<String>> skillRows = allSkills.values().stream()
                    .map(s -> List.of(
                            s.trigger() != null ? s.trigger() : "/skill " + s.name(),
                            s.description(),
                            s.type().name().toLowerCase()))
                    .toList();
            blocks.add(new TableBlock("Skills", List.of("Trigger", "Description", "Type"), skillRows));
        }

        return blocks;
    }
}

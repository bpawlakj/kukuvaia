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
 * <p>A nonempty persona filter lists only registered tools explicitly allowed by that persona.
 * An empty filter leaves all registered tools available, matching AgentService's request scope.
 *
 * <p>Uses ApplicationContext for lazy CommandRegistry lookup to break circular dependency.
 */
@Component
public class HelpCommand implements SlashCommand {

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
            boolean restricted = !persona.toolFilter().isEmpty();
            if (restricted) registered.retainAll(persona.toolFilter());
            if (!registered.isEmpty()) {
                String label = restricted ? "Persona tools (" + persona.name() + "): " : "General tools: ";
                blocks.add(new TextBlock(label + String.join(", ", registered), null));
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

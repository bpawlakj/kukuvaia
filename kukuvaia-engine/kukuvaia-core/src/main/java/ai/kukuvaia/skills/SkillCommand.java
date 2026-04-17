package ai.kukuvaia.skills;

import ai.kukuvaia.commands.SlashCommand;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TableBlock;
import ai.kukuvaia.output.TextBlock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /skill [name] [args] — execute a registered skill.
 * Without arguments, lists all available skills.
 */
@Component
public class SkillCommand implements SlashCommand {

    private final SkillRegistry skillRegistry;
    private final SkillExecutor skillExecutor;

    public SkillCommand(SkillRegistry skillRegistry, SkillExecutor skillExecutor) {
        this.skillRegistry = skillRegistry;
        this.skillExecutor = skillExecutor;
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "Execute a skill (usage: /skill [name] [args])";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        if (args == null || args.isBlank()) {
            return listSkills();
        }

        int spaceIdx = args.indexOf(' ');
        String skillName = spaceIdx > 0 ? args.substring(0, spaceIdx) : args;
        String skillArgs = spaceIdx > 0 ? args.substring(spaceIdx + 1).trim() : "";

        return skillRegistry.resolve(skillName)
                .map(skill -> skillExecutor.execute(skill, skillArgs, sessionId))
                .orElseGet(() -> List.of(new TextBlock(
                        "Unknown skill: %s. Type /skill to list available skills.".formatted(skillName), "error")));
    }

    private List<OutputBlock> listSkills() {
        var allSkills = skillRegistry.allSkills();
        if (allSkills.isEmpty()) {
            return List.of(new TextBlock("No skills registered. Create skills in .kukuvaia/skills/", null));
        }

        List<List<String>> rows = allSkills.values().stream()
                .map(s -> List.of(
                        s.name(),
                        s.description(),
                        s.type().name().toLowerCase(),
                        s.trigger() != null ? s.trigger() : "/skill " + s.name()))
                .toList();

        return List.of(new TableBlock("Available Skills",
                List.of("Name", "Description", "Type", "Trigger"), rows));
    }
}

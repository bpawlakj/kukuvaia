package ai.kukuvaia.skills;

import java.util.List;

/**
 * Skill definition parsed from SKILL.md frontmatter + body.
 * Skills are user-defined actions executed via /skill command or custom triggers.
 */
public record SkillSpec(
        String name,
        String description,
        String trigger,
        SkillType type,
        String language,
        long timeoutMillis,
        List<String> tools,
        String body,
        String scriptSource
) {
    public enum SkillType { PROMPT, SCRIPT }

    public SkillSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Skill name is required");
        if (!name.matches("[a-z0-9][a-z0-9-]*")) throw new IllegalArgumentException("Skill name must match [a-z0-9-]: " + name);
        if (type == null) type = SkillType.PROMPT;
        if (timeoutMillis <= 0) timeoutMillis = 5000;
        if (tools == null) tools = List.of();
        if (type == SkillType.SCRIPT && (scriptSource == null || scriptSource.isBlank())) {
            throw new IllegalArgumentException("Script skills must have a JavaScript code block");
        }
    }
}

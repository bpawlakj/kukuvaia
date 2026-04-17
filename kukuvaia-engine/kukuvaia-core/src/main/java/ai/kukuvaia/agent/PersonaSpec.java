package ai.kukuvaia.agent;

import java.util.List;

/**
 * Persona definition loaded from YAML.
 * Controls system prompt and tool access per session.
 */
public record PersonaSpec(
        String name,
        String description,
        String systemPrompt,
        List<String> toolFilter
) {
    public PersonaSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Persona name is required");
        }
        if (toolFilter == null) toolFilter = List.of();
    }
}

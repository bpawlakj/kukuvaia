package ai.kukuvaia.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Persona definition loaded from YAML.
 * Controls system prompt and tool access per session.
 *
 * <p>YAML uses snake_case ({@code system_prompt}, {@code tools}); the record's components keep
 * camelCase. Jackson bindings + aliases allow either spelling so existing personas authored
 * in either convention keep parsing.
 */
public record PersonaSpec(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("system_prompt") @JsonAlias("systemPrompt") String systemPrompt,
        @JsonProperty("tools") @JsonAlias("toolFilter") List<String> toolFilter
) {
    @JsonCreator
    public PersonaSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Persona name is required");
        }
        if (toolFilter == null) toolFilter = List.of();
    }
}

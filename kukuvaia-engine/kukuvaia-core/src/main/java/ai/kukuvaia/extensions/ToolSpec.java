package ai.kukuvaia.extensions;

import java.util.List;
import java.util.Map;

/**
 * Tool definition parsed from .kukuvaia/tools/{name}/TOOL.md + config.yaml.
 * Uses YAML pipeline steps + optional Lua for logic.
 * Config (API keys, properties) loaded from config.yaml next to TOOL.md.
 */
public record ToolSpec(
        String name,
        String description,
        boolean readOnly,
        boolean concurrencySafe,
        Map<String, ParameterSpec> parameters,
        List<Map<String, Object>> steps,
        Map<String, Object> config
) {
    public record ParameterSpec(
            String type,
            String description,
            boolean required
    ) {}

    public ToolSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name is required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Tool description is required");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("Tool steps are required");
        if (parameters == null) parameters = Map.of();
        if (config == null) config = Map.of();
    }
}

package ai.kukuvaia.provider.dto;

import java.util.Map;
import java.util.Set;

/**
 * Request to register a new LLM provider.
 */
public record CreateProviderRequest(
        String name,
        String type,
        String baseUrl,
        String apiKeyRef,
        int priority,
        Map<String, Object> config
) {

    private static final Set<String> VALID_TYPES = Set.of(
            "smartgate", "openrouter", "openai", "anthropic", "ollama", "custom");

    public CreateProviderRequest {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (type == null || !VALID_TYPES.contains(type)) throw new IllegalArgumentException("type must be one of: " + VALID_TYPES);
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        if (apiKeyRef == null || apiKeyRef.isBlank()) throw new IllegalArgumentException("apiKeyRef required");
        if (config == null) config = Map.of();
    }
}

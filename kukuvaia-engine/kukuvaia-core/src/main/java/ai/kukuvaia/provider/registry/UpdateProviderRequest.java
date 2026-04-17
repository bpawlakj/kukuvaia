package ai.kukuvaia.provider.registry;

import java.util.Map;

/**
 * Request to update an existing LLM provider. All fields optional — only non-null fields are applied.
 */
public record UpdateProviderRequest(
        String name,
        String type,
        String baseUrl,
        String apiKeyRef,
        Boolean enabled,
        Integer priority,
        Map<String, Object> config
) {}

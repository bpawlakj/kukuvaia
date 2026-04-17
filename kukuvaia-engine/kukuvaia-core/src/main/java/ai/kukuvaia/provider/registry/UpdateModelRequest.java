package ai.kukuvaia.provider.registry;

import java.util.List;
import java.util.Map;

/**
 * Request to update model configuration. All fields optional.
 */
public record UpdateModelRequest(
        String displayName,
        List<String> capabilities,
        String tier,
        Integer maxTokens,
        Integer contextWindow,
        Boolean enabled,
        Map<String, Object> config
) {}

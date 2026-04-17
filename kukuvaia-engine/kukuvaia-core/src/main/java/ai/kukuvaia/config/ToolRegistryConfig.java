package ai.kukuvaia.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all available tools indexed by name.
 * Collects ToolCallback instances from Spring AI ToolCallbackProviders
 * and exposes resolution by exact name match.
 *
 * Used by SubAgentFactory to resolve safe tool sets per sub-agent.
 */
@Component
public class ToolRegistryConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryConfig.class);

    private final Map<String, ToolCallback> toolsByName = new ConcurrentHashMap<>();

    public ToolRegistryConfig(Collection<ToolCallbackProvider> providers) {
        for (ToolCallbackProvider provider : providers) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                toolsByName.put(callback.getToolDefinition().name(), callback);
            }
        }
        log.info("Tool registry initialized with {} tools: {}", toolsByName.size(), toolsByName.keySet());
    }

    /**
     * Resolve a single tool by exact name.
     */
    public Optional<ToolCallback> resolve(String toolName) {
        return Optional.ofNullable(toolsByName.get(toolName));
    }

    /**
     * Resolve multiple tools by name, returning only found ones.
     */
    public ToolCallback[] resolveAll(Collection<String> toolNames) {
        return toolNames.stream()
                .map(this::resolve)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toArray(ToolCallback[]::new);
    }

    /**
     * All registered tool names.
     */
    public Collection<String> toolNames() {
        return Collections.unmodifiableCollection(toolsByName.keySet());
    }
}

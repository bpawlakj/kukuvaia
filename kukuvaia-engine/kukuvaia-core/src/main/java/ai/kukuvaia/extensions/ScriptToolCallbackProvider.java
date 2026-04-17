package ai.kukuvaia.extensions;

import ai.kukuvaia.scripting.LuaSandbox;
import ai.kukuvaia.scripting.PipelineEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides ToolCallback instances from YAML+Lua tool definitions.
 * Supports hot reload: refreshCallbacks() rebuilds from ToolLoader.
 * Each tool's config.yaml is injected as 'config' variable in the pipeline.
 */
@Component
public class ScriptToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(ScriptToolCallbackProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolLoader toolLoader;
    private final JdbcTemplate jdbcTemplate;
    private final Path workspaceRoot;
    private final CopyOnWriteArrayList<ToolCallback> callbacks = new CopyOnWriteArrayList<>();

    public ScriptToolCallbackProvider(
            ToolLoader toolLoader,
            JdbcTemplate jdbcTemplate,
            @Value("${kukuvaia.workspace.root:#{systemProperties['user.home'] + '/.kukuvaia/workspace'}}") String root) {
        this.toolLoader = toolLoader;
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRoot = Path.of(root).toAbsolutePath().normalize();
        refreshCallbacks();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return callbacks.toArray(ToolCallback[]::new);
    }

    /**
     * Rebuild callbacks from ToolLoader. Called by ToolWatcher on file changes.
     */
    public void refreshCallbacks() {
        List<ToolSpec> specs = toolLoader.loadTools();
        List<ToolCallback> newCallbacks = new ArrayList<>();

        for (ToolSpec spec : specs) {
            newCallbacks.add(createCallback(spec));
        }

        callbacks.clear();
        callbacks.addAll(newCallbacks);
        log.info("Refreshed {} YAML+Lua tool callbacks: {}",
                callbacks.size(), specs.stream().map(ToolSpec::name).toList());
    }

    private ToolCallback createCallback(ToolSpec spec) {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(spec.name())
                        .description(spec.description())
                        .inputSchema(buildJsonSchema(spec))
                        .build();
            }

            @Override
            @SuppressWarnings("unchecked")
            public String call(String toolInput) {
                log.info("Executing tool '{}' with input: {}", spec.name(), toolInput);

                try {
                    Map<String, Object> args = toolInput != null && !toolInput.isBlank()
                            ? objectMapper.readValue(toolInput, Map.class) : Map.of();

                    // Inject per-tool config into pipeline variables
                    Map<String, Object> variables = new LinkedHashMap<>(args);
                    if (!spec.config().isEmpty()) {
                        variables.put("config", spec.config());
                        // Also flatten config keys as top-level for convenience
                        spec.config().forEach((k, v) -> variables.putIfAbsent(k, v));
                    }

                    var bridge = new ToolBridgeAPI(workspaceRoot, jdbcTemplate);
                    var engine = new PipelineEngine(bridge);
                    return engine.execute(spec.steps(), variables);

                } catch (LuaSandbox.LuaTimeoutException e) {
                    log.warn("Tool '{}' timed out", spec.name());
                    return "{\"error\":\"Tool timed out\"}";
                } catch (Exception e) {
                    log.error("Tool '{}' failed: {}", spec.name(), e.getMessage());
                    return "{\"error\":\"%s\"}".formatted(
                            e.getMessage().replace("\"", "\\\""));
                }
            }
        };
    }

    private String buildJsonSchema(ToolSpec spec) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (var entry : spec.parameters().entrySet()) {
            var param = entry.getValue();
            properties.put(entry.getKey(), Map.of("type", param.type(), "description", param.description()));
            if (param.required()) required.add(entry.getKey());
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);

        try { return objectMapper.writeValueAsString(schema); }
        catch (Exception e) { return "{\"type\":\"object\",\"properties\":{}}"; }
    }
}

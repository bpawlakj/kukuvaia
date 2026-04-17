package ai.kukuvaia.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads tool definitions from .kukuvaia/tools/{name}/TOOL.md + config.yaml.
 * Supports hot reload via reload() method.
 */
@Component
public class ToolLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolLoader.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    private final ExtensionLoader extensionLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private volatile List<ToolSpec> cachedTools = List.of();

    public ToolLoader(ExtensionLoader extensionLoader) {
        this.extensionLoader = extensionLoader;
        reload();
    }

    /**
     * Get currently loaded tools (cached, thread-safe).
     */
    public List<ToolSpec> loadTools() {
        return cachedTools;
    }

    /**
     * Reload all tools from disk. Called at startup and on file changes.
     */
    public synchronized void reload() {
        cachedTools = extensionLoader.getExtensionRoot()
                .map(root -> root.resolve("tools"))
                .filter(Files::isDirectory)
                .map(this::loadFromDirectory)
                .orElseGet(() -> {
                    log.debug("No tools directory found");
                    return List.of();
                });
        log.info("Tool reload complete: {} tools loaded", cachedTools.size());
    }

    private List<ToolSpec> loadFromDirectory(Path toolsDir) {
        List<ToolSpec> tools = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(toolsDir)) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> {
                        Path toolFile = dir.resolve("TOOL.md");
                        if (Files.isRegularFile(toolFile)) {
                            loadToolFile(dir, toolFile).ifPresent(tools::add);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list tools directory: {}", e.getMessage());
        }
        log.info("Loaded {} tools from MD: {}", tools.size(),
                tools.stream().map(ToolSpec::name).toList());
        return tools;
    }

    @SuppressWarnings("unchecked")
    private Optional<ToolSpec> loadToolFile(Path toolDir, Path path) {
        try {
            String content = Files.readString(path);
            Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
            if (!matcher.matches()) {
                log.warn("Tool file has no YAML frontmatter: {}", path);
                return Optional.empty();
            }

            Map<String, Object> fm = yamlMapper.readValue(matcher.group(1), Map.class);
            String body = matcher.group(2).trim();

            String name = (String) fm.get("name");
            if (name == null || name.isBlank()) {
                log.warn("Tool without name in {}", path);
                return Optional.empty();
            }

            // Parse steps from body (YAML list)
            List<Map<String, Object>> steps;
            try {
                steps = yamlMapper.readValue(body, List.class);
            } catch (Exception e) {
                log.error("Tool '{}' has invalid steps YAML: {}", name, e.getMessage());
                return Optional.empty();
            }

            if (steps == null || steps.isEmpty()) {
                log.error("Tool '{}' has no steps", name);
                return Optional.empty();
            }

            // Parse parameters
            Map<String, ToolSpec.ParameterSpec> parameters = new LinkedHashMap<>();
            Object paramsObj = fm.get("parameters");
            if (paramsObj instanceof Map) {
                ((Map<String, Object>) paramsObj).forEach((paramName, paramDef) -> {
                    if (paramDef instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> def = (Map<String, Object>) raw;
                        parameters.put(paramName, new ToolSpec.ParameterSpec(
                                (String) def.getOrDefault("type", "string"),
                                (String) def.getOrDefault("description", ""),
                                Boolean.TRUE.equals(def.get("required"))
                        ));
                    }
                });
            }

            // Load per-tool config from config.yaml
            Map<String, Object> config = loadConfig(toolDir);

            var spec = new ToolSpec(
                    name,
                    (String) fm.getOrDefault("description", ""),
                    Boolean.TRUE.equals(fm.get("readOnly")),
                    Boolean.TRUE.equals(fm.get("concurrencySafe")),
                    parameters,
                    steps,
                    config
            );

            log.info("Loaded tool '{}' ({} steps, {} config keys) from {}",
                    name, steps.size(), config.size(), path.getFileName());
            return Optional.of(spec);

        } catch (Exception e) {
            log.warn("Failed to load tool from {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig(Path toolDir) {
        Path configFile = toolDir.resolve("config.yaml");
        if (!Files.isRegularFile(configFile)) {
            configFile = toolDir.resolve("config.yml");
        }
        if (!Files.isRegularFile(configFile)) {
            return Map.of();
        }
        try {
            Map<String, Object> config = yamlMapper.readValue(configFile.toFile(), Map.class);
            log.debug("Loaded config for tool in {}: {} keys", toolDir.getFileName(), config.size());
            return config != null ? config : Map.of();
        } catch (Exception e) {
            log.warn("Failed to load config.yaml in {}: {}", toolDir, e.getMessage());
            return Map.of();
        }
    }
}

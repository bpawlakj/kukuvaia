package ai.kukuvaia.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads specialist definitions from built-in resources and user .kukuvaia/ directory.
 * User-defined specialists override built-in ones with the same name.
 */
@Component
public class SubAgentSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSpecLoader.class);

    @Value("${kukuvaia.project-dir:}")
    private String projectDir;

    @SuppressWarnings("unchecked")
    public Map<String, SubAgentSpec> loadAll() {
        Map<String, SubAgentSpec> specs = new LinkedHashMap<>();
        Yaml yaml = new Yaml();

        // Load built-in specialists from classpath
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:specialists/*.yaml");
            for (Resource resource : resources) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = yaml.load(is);
                    SubAgentSpec spec = parseSpec(data);
                    specs.put(spec.name(), spec);
                    log.info("Loaded built-in specialist: {}", spec.name());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load built-in specialists: {}", e.getMessage());
        }

        // Load user-defined specialists (override built-in)
        if (projectDir != null && !projectDir.isBlank()) {
            Path userDir = Path.of(projectDir, ".kukuvaia", "specialists");
            if (Files.isDirectory(userDir)) {
                try (var stream = Files.list(userDir)) {
                    stream.filter(p -> p.toString().endsWith(".yaml")).forEach(path -> {
                        try (InputStream is = Files.newInputStream(path)) {
                            Map<String, Object> data = yaml.load(is);
                            SubAgentSpec spec = parseSpec(data);
                            if (specs.containsKey(spec.name())) {
                                log.info("User specialist '{}' overrides built-in", spec.name());
                            }
                            specs.put(spec.name(), spec);
                        } catch (IOException e) {
                            log.warn("Failed to load user specialist {}: {}",
                                    path.getFileName(), e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    log.warn("Failed to list user specialists: {}", e.getMessage());
                }
            }
        }

        log.info("Loaded {} specialist definitions", specs.size());
        return Collections.unmodifiableMap(specs);
    }

    @SuppressWarnings("unchecked")
    private SubAgentSpec parseSpec(Map<String, Object> data) {
        return new SubAgentSpec(
                (String) data.get("name"),
                (String) data.getOrDefault("description", ""),
                (String) data.get("provider"), // null = inherit
                (String) data.get("tier"),     // null = use model field
                (String) data.getOrDefault("system_prompt", ""),
                data.containsKey("tools")
                        ? (List<String>) data.get("tools")
                        : List.of(),
                (String) data.getOrDefault("model", "claude-sonnet"),
                ((Number) data.getOrDefault("max_tokens", 4096)).intValue(),
                ((Number) data.getOrDefault("max_tool_rounds", 10)).intValue(),
                ((Number) data.getOrDefault("temperature", 0.1)).doubleValue(),
                ((Number) data.getOrDefault("timeout_seconds", 0)).longValue()
        );
    }
}

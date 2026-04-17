package ai.kukuvaia.skills;

import ai.kukuvaia.extensions.ExtensionLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads skill definitions from .kukuvaia/skills/{name}/SKILL.md files.
 * Parses YAML frontmatter and extracts JavaScript code blocks from markdown body.
 */
@Component
public class SkillsLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillsLoader.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    private static final Pattern SCRIPT_BLOCK_PATTERN = Pattern.compile("```(?:lua|javascript)\\s*\\n(.*?)\\n```", Pattern.DOTALL);

    private final ExtensionLoader extensionLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public SkillsLoader(ExtensionLoader extensionLoader) {
        this.extensionLoader = extensionLoader;
    }

    public List<SkillSpec> loadSkills() {
        return extensionLoader.getSkillsDir()
                .map(this::loadFromDirectory)
                .orElseGet(() -> {
                    log.debug("No skills directory found");
                    return List.of();
                });
    }

    private List<SkillSpec> loadFromDirectory(Path skillsDir) {
        List<SkillSpec> skills = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                    .forEach(dir -> {
                        Path skillFile = dir.resolve("SKILL.md");
                        if (Files.isRegularFile(skillFile)) {
                            loadSkillFile(skillFile).ifPresent(skills::add);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list skills directory: {}", e.getMessage());
        }
        log.info("Loaded {} skills from {}", skills.size(), skillsDir);
        return skills;
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<SkillSpec> loadSkillFile(Path path) {
        try {
            String content = Files.readString(path);
            Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
            if (!matcher.matches()) {
                log.warn("Skill file has no YAML frontmatter: {}", path);
                return java.util.Optional.empty();
            }

            String frontmatterYaml = matcher.group(1);
            String body = matcher.group(2);

            Map<String, Object> frontmatter = yamlMapper.readValue(frontmatterYaml, Map.class);

            String name = (String) frontmatter.get("name");
            if (name == null || name.isBlank()) {
                log.warn("Skill without name in {}", path);
                return java.util.Optional.empty();
            }

            String typeStr = (String) frontmatter.getOrDefault("type", "prompt");
            SkillSpec.SkillType type = "script".equalsIgnoreCase(typeStr)
                    ? SkillSpec.SkillType.SCRIPT : SkillSpec.SkillType.PROMPT;

            // Extract JavaScript code block from body
            String scriptSource = null;
            Matcher jsMatcher = SCRIPT_BLOCK_PATTERN.matcher(body);
            if (jsMatcher.find()) {
                scriptSource = jsMatcher.group(1);
            }

            if (type == SkillSpec.SkillType.SCRIPT && (scriptSource == null || scriptSource.isBlank())) {
                log.error("Script skill '{}' has no JavaScript code block: {}", name, path);
                return java.util.Optional.empty();
            }

            Object toolsObj = frontmatter.get("tools");
            List<String> tools = toolsObj instanceof List ? (List<String>) toolsObj : List.of();

            long timeout = frontmatter.containsKey("timeout")
                    ? ((Number) frontmatter.get("timeout")).longValue() : 5000;

            var spec = new SkillSpec(
                    name,
                    (String) frontmatter.getOrDefault("description", ""),
                    (String) frontmatter.get("trigger"),
                    type,
                    (String) frontmatter.getOrDefault("language", "javascript"),
                    timeout,
                    tools,
                    body,
                    scriptSource
            );

            log.info("Loaded skill '{}' (type={}) from {}", name, type, path.getFileName());
            return java.util.Optional.of(spec);

        } catch (Exception e) {
            log.warn("Failed to load skill from {}: {}", path, e.getMessage());
            return java.util.Optional.empty();
        }
    }
}

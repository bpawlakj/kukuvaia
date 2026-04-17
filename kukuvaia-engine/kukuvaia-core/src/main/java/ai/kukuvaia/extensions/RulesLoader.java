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
 * Loads structured rules from .kukuvaia/rules/*.md files.
 * Rules control LLM behavior: constraints, response style (thinking/concise), output format.
 *
 * Supports two formats:
 * 1. Structured: MD with YAML frontmatter (name, type, scope, priority, etc.)
 * 2. Plain: Raw MD without frontmatter (treated as global constraint, priority 50)
 */
@Component
public class RulesLoader {

    private static final Logger log = LoggerFactory.getLogger(RulesLoader.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    private final ExtensionLoader extensionLoader;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final List<RuleSpec> rules = new ArrayList<>();

    public RulesLoader(ExtensionLoader extensionLoader) {
        this.extensionLoader = extensionLoader;
        reload();
    }

    /**
     * Get all rules applicable to the given context, sorted by priority (highest first).
     */
    public List<RuleSpec> getApplicableRules(String activePersona, String detectedIntent) {
        return rules.stream()
                .filter(r -> r.appliesTo(activePersona, detectedIntent))
                .sorted(Comparator.comparingInt(RuleSpec::priority).reversed())
                .toList();
    }

    /**
     * Build a combined rules text block for system prompt injection.
     * Groups rules by type for clear LLM understanding.
     */
    public String buildRulesPrompt(String activePersona, String detectedIntent) {
        var applicable = getApplicableRules(activePersona, detectedIntent);
        if (applicable.isEmpty()) return "";

        var sb = new StringBuilder();

        var constraints = applicable.stream().filter(r -> r.type() == RuleSpec.RuleType.CONSTRAINT).toList();
        var behaviors = applicable.stream().filter(r -> r.type() == RuleSpec.RuleType.BEHAVIOR).toList();
        var formats = applicable.stream().filter(r -> r.type() == RuleSpec.RuleType.FORMAT).toList();

        if (!constraints.isEmpty()) {
            sb.append("\n## Constraints (non-negotiable)\n");
            constraints.forEach(r -> sb.append("\n### ").append(r.name()).append("\n").append(r.body()).append("\n"));
        }

        if (!behaviors.isEmpty()) {
            sb.append("\n## Behavior Directives\n");
            behaviors.forEach(r -> sb.append("\n### ").append(r.name()).append("\n").append(r.body()).append("\n"));
        }

        if (!formats.isEmpty()) {
            sb.append("\n## Output Format Requirements\n");
            formats.forEach(r -> sb.append("\n### ").append(r.name()).append("\n").append(r.body()).append("\n"));
        }

        return sb.toString();
    }

    /**
     * Legacy method: load all rules as concatenated text (backward compatible).
     */
    public String loadRules() {
        return buildRulesPrompt(null, null);
    }

    public List<RuleSpec> allRules() {
        return List.copyOf(rules);
    }

    public void reload() {
        rules.clear();
        extensionLoader.getRulesDir().ifPresent(this::loadFromDirectory);
        log.info("Loaded {} rules (constraints={}, behaviors={}, formats={})",
                rules.size(),
                rules.stream().filter(r -> r.type() == RuleSpec.RuleType.CONSTRAINT).count(),
                rules.stream().filter(r -> r.type() == RuleSpec.RuleType.BEHAVIOR).count(),
                rules.stream().filter(r -> r.type() == RuleSpec.RuleType.FORMAT).count());
    }

    private void loadFromDirectory(Path rulesDir) {
        try (Stream<Path> files = Files.list(rulesDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(this::loadRuleFile);
        } catch (IOException e) {
            log.warn("Failed to list rules directory: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadRuleFile(Path path) {
        try {
            String content = Files.readString(path);
            Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

            if (matcher.matches()) {
                // Structured rule with frontmatter
                String frontmatterYaml = matcher.group(1);
                String body = matcher.group(2).trim();
                Map<String, Object> fm = yamlMapper.readValue(frontmatterYaml, Map.class);

                String name = (String) fm.getOrDefault("name", path.getFileName().toString().replace(".md", ""));
                String typeStr = (String) fm.getOrDefault("type", "constraint");
                String scopeStr = (String) fm.getOrDefault("scope", "global");
                Object intentsObj = fm.get("intents");
                List<String> intents = intentsObj instanceof List ? (List<String>) intentsObj : List.of();
                // Support single "when" field as shorthand: "when: intent == ANALYSIS"
                String when = (String) fm.get("when");
                if (when != null && when.startsWith("intent == ")) {
                    intents = List.of(when.substring("intent == ".length()));
                }

                rules.add(new RuleSpec(
                        name,
                        (String) fm.getOrDefault("description", ""),
                        parseType(typeStr),
                        parseScope(scopeStr),
                        fm.containsKey("priority") ? ((Number) fm.get("priority")).intValue() : 50,
                        (String) fm.get("persona"),
                        intents,
                        body
                ));
            } else {
                // Plain MD without frontmatter — treat as global constraint
                String body = content.trim();
                if (!body.isBlank()) {
                    rules.add(new RuleSpec(
                            path.getFileName().toString().replace(".md", ""),
                            "Rule from " + path.getFileName(),
                            RuleSpec.RuleType.CONSTRAINT,
                            RuleSpec.RuleScope.GLOBAL,
                            50,
                            null,
                            List.of(),
                            body
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load rule from {}: {}", path, e.getMessage());
        }
    }

    private RuleSpec.RuleType parseType(String type) {
        return switch (type.toLowerCase()) {
            case "behavior" -> RuleSpec.RuleType.BEHAVIOR;
            case "format" -> RuleSpec.RuleType.FORMAT;
            default -> RuleSpec.RuleType.CONSTRAINT;
        };
    }

    private RuleSpec.RuleScope parseScope(String scope) {
        return switch (scope.toLowerCase()) {
            case "persona" -> RuleSpec.RuleScope.PERSONA;
            case "intent" -> RuleSpec.RuleScope.INTENT;
            default -> RuleSpec.RuleScope.GLOBAL;
        };
    }
}

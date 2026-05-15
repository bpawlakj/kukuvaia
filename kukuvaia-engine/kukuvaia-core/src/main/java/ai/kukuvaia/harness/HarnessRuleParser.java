package ai.kukuvaia.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses harness rule markdown documents (YAML frontmatter + body). Mirrors the conventions used
 * for personas and {@code TOOL.md} so a single mental model covers everything in {@code .kukuvaia/}.
 *
 * <p>Frontmatter is delimited by lines containing only {@code ---}. The body — the markdown after
 * the closing delimiter — becomes {@link HarnessRule#content()}. Frontmatter keys map directly to
 * record fields, with {@code key} defaulting to the rule name and {@code scope} defaulting to
 * {@code "platform"}.
 */
final class HarnessRuleParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private HarnessRuleParser() {}

    /**
     * Parse one markdown document. {@code name} comes from the caller (typically derived from the
     * filename or storage key), not from the frontmatter — the store owns identity, not the file.
     *
     * @throws IllegalArgumentException if the document is missing frontmatter, missing required
     *                                  fields, or the body is empty
     */
    @SuppressWarnings("unchecked")
    static HarnessRule parse(String name, String rawMarkdown) {
        if (rawMarkdown == null) {
            throw new IllegalArgumentException("rule '" + name + "': empty document");
        }
        String[] split = splitFrontmatter(name, rawMarkdown);
        String frontmatterYaml = split[0];
        String body = split[1].strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("rule '" + name + "': body must not be empty");
        }

        Map<String, Object> meta;
        try {
            meta = YAML.readValue(frontmatterYaml, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("rule '" + name + "': invalid YAML frontmatter — "
                    + e.getMessage(), e);
        }
        if (meta == null) meta = Map.of();

        String type = stringOr(meta, "type", null);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("rule '" + name + "': frontmatter 'type' is required "
                    + "(instruction|constraint|context|preference)");
        }
        String scope = stringOr(meta, "scope", HarnessRule.SCOPE_PLATFORM);
        String key = stringOr(meta, "key", name);
        int priority = intOr(meta, "priority", 0);
        boolean enabled = boolOr(meta, "enabled", true);
        List<String> tags = listOr(meta, "tags");

        return new HarnessRule(name, key, scope, type, body, priority, enabled, tags);
    }

    /**
     * Render a {@link HarnessRule} back to markdown — used by the admin panel "save" path so that
     * round-trip (read → edit → write) preserves the on-disk format consistently.
     */
    static String render(HarnessRule rule) {
        var sb = new StringBuilder("---\n");
        sb.append("key: ").append(rule.key()).append('\n');
        sb.append("scope: ").append(rule.scope()).append('\n');
        sb.append("type: ").append(rule.type()).append('\n');
        sb.append("priority: ").append(rule.priority()).append('\n');
        sb.append("enabled: ").append(rule.enabled()).append('\n');
        if (!rule.tags().isEmpty()) {
            sb.append("tags: [").append(String.join(", ", rule.tags())).append("]\n");
        }
        sb.append("---\n");
        sb.append(rule.content());
        if (!rule.content().endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    private static String[] splitFrontmatter(String name, String raw) {
        // Tolerate Windows line endings by normalising at the seam — markdown bodies are kept
        // verbatim aside from leading/trailing whitespace stripping done by the caller.
        String normalised = raw.replace("\r\n", "\n");
        if (!normalised.startsWith("---\n") && !normalised.equals("---")) {
            throw new IllegalArgumentException("rule '" + name + "': must start with '---' frontmatter delimiter");
        }
        int closing = normalised.indexOf("\n---", 4);
        if (closing < 0) {
            throw new IllegalArgumentException("rule '" + name + "': frontmatter not closed (missing '---' delimiter)");
        }
        String front = normalised.substring(4, closing);
        // Skip the closing delimiter and any trailing newline so the body starts at real content.
        int bodyStart = closing + 4;
        if (bodyStart < normalised.length() && normalised.charAt(bodyStart) == '\n') bodyStart++;
        String body = bodyStart < normalised.length() ? normalised.substring(bodyStart) : "";
        return new String[] {front, body};
    }

    private static String stringOr(Map<String, Object> meta, String key, String fallback) {
        Object v = meta.get(key);
        return v == null ? fallback : v.toString();
    }

    private static int intOr(Map<String, Object> meta, String key, int fallback) {
        Object v = meta.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private static boolean boolOr(Map<String, Object> meta, String key, boolean fallback) {
        Object v = meta.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOr(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> raw) {
            List<String> out = new ArrayList<>(raw.size());
            for (Object o : raw) if (o != null) out.add(o.toString());
            return out;
        }
        if (v instanceof String s) return List.of(s);
        return List.of();
    }
}

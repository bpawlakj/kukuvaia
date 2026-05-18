package ai.kukuvaia.advisors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Registers built-in {@link ToolCompactSummary} implementations for the high-payload MCP
 * tools called out in {@code T16} (P24 Phase C). Tool-owning teams from other modules can
 * register their own by injecting {@link ToolCompactionRegistry} and calling
 * {@code registry.register(...)} at startup.
 *
 * <p>Each summary here is defensive: parses the response JSON best-effort, returns
 * {@code null} on any parse failure so the strategy falls through to default elision.
 * Tools whose actual response shape diverges from our assumptions still get usefully
 * compacted (just via the marker, not the bespoke summary).
 */
@Configuration
public class ToolSummariesConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolSummariesConfiguration.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCompactionRegistry registry;

    public ToolSummariesConfiguration(ToolCompactionRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void registerBuiltIns() {
        registry.register("introspect_section_schema", introspectSectionSchemaSummary());
        registry.register("get_outline_sections", getOutlineSectionsSummary());
        registry.register("get_outline_snapshot", getOutlineSnapshotSummary());
        log.info("Registered {} built-in tool compaction summaries", registry.size());
    }

    /**
     * Compact summary for {@code introspect_section_schema} — the canonical Phase C case
     * from T16. The MCP response is a schema dump (templates, sectionTypes, specs); we
     * extract the headline counts.
     */
    static ToolCompactSummary introspectSectionSchemaSummary() {
        return (args, data) -> {
            try {
                JsonNode node = MAPPER.readTree(data);
                String template = extractArgValue(args, "templateId", "template");
                int sectionTypes = countArrayOrObject(node, "sectionTypes");
                int booleanSpecs = countByCondition(node, "boolean");
                int taxonomySpecs = countByCondition(node, "taxonomy");
                int totalSpecs = countArrayOrObject(node, "specs");
                StringBuilder sb = new StringBuilder("schema");
                if (template != null) sb.append(" for template ").append(template);
                sb.append(": ");
                if (sectionTypes > 0) sb.append(sectionTypes).append(" sectionTypes");
                if (totalSpecs > 0) sb.append(", ").append(totalSpecs).append(" specs");
                if (booleanSpecs > 0) sb.append(" (").append(booleanSpecs).append(" boolean");
                if (taxonomySpecs > 0) sb.append(", ").append(taxonomySpecs).append(" taxonomy");
                if (booleanSpecs > 0 || taxonomySpecs > 0) sb.append(")");
                sb.append(". Re-call this tool for full schema.");
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        };
    }

    /**
     * Compact summary for {@code get_outline_sections}. Response is typically a list of
     * sections with id/title/type fields; we keep the first few titles and a count.
     */
    static ToolCompactSummary getOutlineSectionsSummary() {
        return (args, data) -> {
            try {
                JsonNode node = MAPPER.readTree(data);
                JsonNode sections = node.has("sections") ? node.get("sections") : node;
                if (!sections.isArray()) return null;
                int total = sections.size();
                StringBuilder titles = new StringBuilder();
                int sampled = 0;
                for (int i = 0; i < total && sampled < 5; i++) {
                    JsonNode s = sections.get(i);
                    String title = textOrNull(s, "title", "name", "id");
                    if (title != null) {
                        if (sampled > 0) titles.append(", ");
                        titles.append('"').append(title).append('"');
                        sampled++;
                    }
                }
                String outline = extractArgValue(args, "outlineId", "outline");
                return String.format(
                        "%d sections%s%s. Re-call with offset for full list.",
                        total,
                        outline != null ? " for outline " + outline : "",
                        sampled > 0 ? " — first " + sampled + ": [" + titles + "]" : "");
            } catch (Exception e) {
                return null;
            }
        };
    }

    /**
     * Compact summary for {@code get_outline_snapshot}. This tool returns megabytes —
     * persona prompts already direct agents not to call it for retention. Our summary
     * underscores that: keep a size marker and the "re-call if needed" pointer.
     */
    static ToolCompactSummary getOutlineSnapshotSummary() {
        return (args, data) -> {
            String outline = extractArgValue(args, "outlineId", "outline");
            return String.format(
                    "outline snapshot%s elided (%d chars). Re-call only if absolutely necessary; "
                            + "persona guidance is to use targeted accessors instead.",
                    outline != null ? " for " + outline : "",
                    data.length());
        };
    }

    private static String extractArgValue(String argsJson, String... keys) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            JsonNode args = MAPPER.readTree(argsJson);
            for (String key : keys) {
                JsonNode v = args.get(key);
                if (v != null && !v.isNull()) return v.asText();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int countArrayOrObject(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null) return 0;
        if (n.isArray()) return n.size();
        if (n.isObject()) return n.size();
        return 0;
    }

    private static int countByCondition(JsonNode root, String typeKeyword) {
        JsonNode specs = root.has("specs") ? root.get("specs") : root;
        if (!specs.isArray()) return 0;
        int count = 0;
        for (JsonNode s : specs) {
            String type = textOrNull(s, "type", "kind", "category");
            if (type != null && type.toLowerCase().contains(typeKeyword)) count++;
        }
        return count;
    }

    private static String textOrNull(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && v.isValueNode()) return v.asText();
        }
        return null;
    }
}

package ai.kukuvaia.agent.daemon.def;

import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses one daemon job YAML document with a strict schema: unknown keys, wrong types and invalid
 * values are rejected with a message naming the file and the key.
 *
 * <pre>
 * name: ci-failure-analysis          # [a-z0-9-], 2–64 chars
 * description: Explain failed CI runs
 * kind: specialist                   # specialist | java | embabel | mcp | a2a
 * target: analyst                    # or, for mcp: { connection: x, tool: y }
 * input: { fields: [repository, branch, status, error] }
 * prompt: "CI failed on {{fields.repository}}"   # only {{fields.declared}} placeholders
 * triggers: [ { cron: "0 0 2 * * MON-FRI" }, { webhook: ci }, { pg-notify: new_content_items }, { manual: true } ]
 * provider: null                     # explicit opt-in, e.g. copilot
 * limits: { timeout: 120s, max_tokens: 4000, max_tool_rounds: 6, max_actions: 20, token_budget: 20000 }
 * overlap: skip                      # skip | allow | { max_concurrent: 2 }
 * notify: [log]
 * enabled: true
 * </pre>
 */
public final class DaemonJobParser {

    private static final Set<String> TOP_LEVEL = Set.of("name", "description", "kind", "target", "input", "prompt",
            "triggers", "provider", "limits", "overlap", "notify", "enabled", "args");
    private static final int MAX_ARGS = 32;
    private static final Set<String> LIMIT_KEYS = Set.of("timeout", "max_tokens", "max_tool_rounds", "max_actions", "token_budget");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final Pattern ROUTE = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final Pattern CHANNEL = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern SINK = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern PROVIDER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private DaemonJobParser() {
    }

    /** Thrown for any invalid definition; the message is safe to log. */
    public static final class InvalidJobDefinitionException extends RuntimeException {
        public InvalidJobDefinitionException(String source, String message) {
            super(source + ": " + message);
        }
    }

    public static DaemonJobDefinition parse(String yaml, String source) {
        Map<String, Object> doc;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = YAML.readValue(yaml, Map.class);
            doc = parsed;
        } catch (JacksonException e) {
            throw new InvalidJobDefinitionException(source, "not valid YAML (" + e.getOriginalMessage() + ")");
        }
        if (doc == null) throw new InvalidJobDefinitionException(source, "empty document");
        rejectUnknown(source, "", doc.keySet(), TOP_LEVEL);

        String name = requiredString(source, doc, "name");
        if (!NAME.matcher(name).matches()) fail(source, "name '" + name + "' must match " + NAME.pattern());
        JobKind kind;
        try {
            kind = JobKind.fromString(requiredString(source, doc, "kind"));
        } catch (IllegalArgumentException e) {
            throw new InvalidJobDefinitionException(source, "kind must be one of specialist, java, embabel, mcp, a2a");
        }
        String target = parseTarget(source, kind, doc.get("target"));
        List<String> fields = parseFields(source, doc.get("input"));
        Map<String, List<String>> match = parseMatch(source, doc.get("input"), fields);
        String prompt = optionalString(source, doc, "prompt");
        Set<String> invalid = PromptRenderer.invalidPlaceholders(prompt, new HashSet<>(fields));
        if (!invalid.isEmpty()) {
            fail(source, "prompt uses placeholders that are not declared input fields: " + invalid
                    + " (only {{fields.<name>}} for fields listed under input.fields)");
        }
        List<DaemonTrigger> triggers = parseTriggers(source, doc.get("triggers"));
        String provider = optionalString(source, doc, "provider");
        if (provider != null && !PROVIDER.matcher(provider).matches()) {
            fail(source, "provider must match " + PROVIDER.pattern());
        }
        JobLimits limits = parseLimits(source, doc.get("limits"));
        Overlap overlap = parseOverlap(source, doc.get("overlap"));
        List<String> notify = parseStringList(source, doc.get("notify"), "notify", SINK);
        boolean enabled = doc.get("enabled") == null || bool(source, doc.get("enabled"), "enabled");
        String description = optionalString(source, doc, "description");

        Map<String, Object> args = parseArgs(source, kind, doc.get("args"), fields);

        return new DaemonJobDefinition(name, description, kind, target, fields, prompt, triggers, provider,
                limits, overlap, notify.isEmpty() ? List.of("log") : notify, enabled, source, match, args);
    }

    /**
     * {@code args:} for {@code kind: mcp} — a flat map of scalars. String values may use
     * {@code {{fields.x}}} for declared fields only; arguments are built as typed values, never by
     * splicing text into JSON.
     */
    private static Map<String, Object> parseArgs(String source, JobKind kind, Object raw, List<String> fields) {
        if (raw == null) return Map.of();
        if (kind != JobKind.MCP) fail(source, "args is only supported for kind: mcp");
        if (!(raw instanceof Map<?, ?> map)) {
            fail(source, "args must be a map of argument name to value");
            return Map.of();
        }
        if (map.size() > MAX_ARGS) fail(source, "args may have at most " + MAX_ARGS + " entries");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (var e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!FIELD.matcher(key).matches()) fail(source, "args key '" + key + "' must match " + FIELD.pattern());
            Object value = e.getValue();
            if (value instanceof String s) {
                Set<String> invalid = PromptRenderer.invalidPlaceholders(s, new HashSet<>(fields));
                if (!invalid.isEmpty()) {
                    fail(source, "args." + key + " uses placeholders that are not declared input fields: " + invalid);
                }
            } else if (!(value instanceof Number) && !(value instanceof Boolean)) {
                fail(source, "args." + key + " must be a string, number or boolean");
            }
            out.put(key, value);
        }
        return out;
    }

    private static String parseTarget(String source, JobKind kind, Object raw) {
        if (raw instanceof Map<?, ?> map) {
            if (kind != JobKind.MCP && kind != JobKind.A2A) fail(source, "target as a map is only valid for kind mcp/a2a");
            rejectUnknown(source, "target.", keys(map), Set.of("connection", "tool", "agent"));
            Object connection = map.get("connection");
            Object tool = kind == JobKind.MCP ? map.get("tool") : map.get("agent");
            if (!(connection instanceof String c) || c.isBlank() || !(tool instanceof String t) || t.isBlank()) {
                fail(source, "target needs connection and " + (kind == JobKind.MCP ? "tool" : "agent"));
                return null;
            }
            return c.trim() + "/" + t.trim();
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            fail(source, "target is required");
            return null;
        }
        return s.trim();
    }

    private static List<String> parseFields(String source, Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof Map<?, ?> map)) {
            fail(source, "input must be a map with 'fields'");
            return List.of();
        }
        rejectUnknown(source, "input.", keys(map), Set.of("fields", "match"));
        return parseStringList(source, map.get("fields"), "input.fields", FIELD);
    }

    private static Map<String, List<String>> parseMatch(String source, Object input, List<String> fields) {
        if (!(input instanceof Map<?, ?> map) || map.get("match") == null) return Map.of();
        if (!(map.get("match") instanceof Map<?, ?> match)) {
            fail(source, "input.match must be a map of field -> list of values");
            return Map.of();
        }
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (var entry : match.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (!fields.contains(field)) fail(source, "input.match field '" + field + "' is not a declared input field");
            List<String> values = parseStringList(source, entry.getValue(), "input.match." + field,
                    Pattern.compile("[A-Za-z0-9_.-]{1,64}"));
            if (values.isEmpty()) fail(source, "input.match." + field + " needs at least one value");
            out.put(field, values);
        }
        return out;
    }

    private static List<DaemonTrigger> parseTriggers(String source, Object raw) {
        if (raw == null) return List.of(new DaemonTrigger.Manual());
        if (!(raw instanceof List<?> list)) {
            fail(source, "triggers must be a list");
            return List.of();
        }
        List<DaemonTrigger> triggers = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map) || map.size() != 1) {
                fail(source, "each trigger is a single-key map: {cron: ...} | {webhook: ...} | {pg-notify: ...} | {manual: true}");
                continue;
            }
            var entry = map.entrySet().iterator().next();
            String type = String.valueOf(entry.getKey());
            String value = entry.getValue() == null ? null : String.valueOf(entry.getValue()).trim();
            switch (type) {
                case "cron" -> {
                    if (value == null || !CronExpression.isValidExpression(value)) {
                        fail(source, "invalid cron expression '" + value + "' (6 fields: sec min hour day month weekday)");
                    }
                    triggers.add(new DaemonTrigger.Cron(value));
                }
                case "webhook" -> {
                    if (value == null || !ROUTE.matcher(value).matches()) fail(source, "webhook route must match " + ROUTE.pattern());
                    triggers.add(new DaemonTrigger.Webhook(value));
                }
                case "pg-notify" -> {
                    if (value == null || !CHANNEL.matcher(value).matches()) fail(source, "pg-notify channel must match " + CHANNEL.pattern());
                    triggers.add(new DaemonTrigger.PgNotify(value));
                }
                case "manual" -> {
                    if (bool(source, entry.getValue(), "manual")) triggers.add(new DaemonTrigger.Manual());
                }
                default -> fail(source, "unknown trigger type '" + type + "'");
            }
        }
        return triggers;
    }

    private static JobLimits parseLimits(String source, Object raw) {
        if (raw == null) return JobLimits.defaults();
        if (!(raw instanceof Map<?, ?> map)) {
            fail(source, "limits must be a map");
            return JobLimits.defaults();
        }
        rejectUnknown(source, "limits.", keys(map), LIMIT_KEYS);
        Duration timeout = JobLimits.DEFAULT_TIMEOUT;
        if (map.get("timeout") != null) {
            try {
                timeout = DurationStyle.detectAndParse(String.valueOf(map.get("timeout")), ChronoUnit.SECONDS);
            } catch (IllegalArgumentException e) {
                fail(source, "limits.timeout '" + map.get("timeout") + "' is not a duration (e.g. 120s, 2m)");
            }
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(2)) > 0) {
                fail(source, "limits.timeout must be between 1s and 2h");
            }
        }
        return new JobLimits(timeout, nonNegative(source, map, "max_tokens"), nonNegative(source, map, "max_tool_rounds"),
                nonNegative(source, map, "max_actions"), nonNegative(source, map, "token_budget"));
    }

    private static Overlap parseOverlap(String source, Object raw) {
        if (raw == null) return Overlap.skip();
        if (raw instanceof String s) {
            return switch (s.trim()) {
                case "skip" -> Overlap.skip();
                case "allow" -> Overlap.allow(1);
                default -> {
                    fail(source, "overlap must be skip, allow, or {max_concurrent: N}");
                    yield Overlap.skip();
                }
            };
        }
        if (raw instanceof Map<?, ?> map) {
            rejectUnknown(source, "overlap.", keys(map), Set.of("max_concurrent"));
            int max = nonNegative(source, map, "max_concurrent");
            if (max < 1 || max > 50) fail(source, "overlap.max_concurrent must be 1..50");
            return Overlap.allow(max);
        }
        fail(source, "overlap must be skip, allow, or {max_concurrent: N}");
        return Overlap.skip();
    }

    private static List<String> parseStringList(String source, Object raw, String key, Pattern allowed) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            fail(source, key + " must be a list");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String value = item == null ? "" : String.valueOf(item).trim();
            if (!allowed.matcher(value).matches()) fail(source, key + " entry '" + value + "' must match " + allowed.pattern());
            out.add(value);
        }
        return out;
    }

    private static int nonNegative(String source, Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return 0;
        if (!(v instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > Integer.MAX_VALUE
                || n.doubleValue() != Math.floor(n.doubleValue())) {
            fail(source, key + " must be a non-negative integer (max " + Integer.MAX_VALUE + ")");
            return 0;
        }
        return n.intValue();
    }

    private static boolean bool(String source, Object v, String key) {
        if (v instanceof Boolean b) return b;
        fail(source, key + " must be true or false");
        return false;
    }

    private static String requiredString(String source, Map<String, Object> doc, String key) {
        String value = optionalString(source, doc, key);
        if (value == null || value.isBlank()) fail(source, key + " is required");
        return value;
    }

    private static String optionalString(String source, Map<String, Object> doc, String key) {
        Object v = doc.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            fail(source, key + " must be a string");
            return null;
        }
        return s;
    }

    private static void rejectUnknown(String source, String prefix, Set<String> actual, Set<String> allowed) {
        for (String key : actual) {
            if (!allowed.contains(key)) fail(source, "unknown key '" + prefix + key + "' (allowed: " + allowed + ")");
        }
    }

    private static Set<String> keys(Map<?, ?> map) {
        Set<String> out = new HashSet<>();
        map.keySet().forEach(k -> out.add(String.valueOf(k)));
        return out;
    }

    private static void fail(String source, String message) {
        throw new InvalidJobDefinitionException(source, message);
    }
}

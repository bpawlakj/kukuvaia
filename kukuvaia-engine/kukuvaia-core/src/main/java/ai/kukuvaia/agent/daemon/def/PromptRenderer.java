package ai.kukuvaia.agent.daemon.def;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a job prompt. The only placeholder is {@code {{fields.<name>}}} and only declared input
 * fields are substituted — a raw trigger payload can never be interpolated (AGENTS.md "never
 * interpolate raw ${event.payload}"). Field values are expected to be sanitized already and are
 * additionally flattened to one line (see {@link #flatten}).
 */
public final class PromptRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*fields\\.([A-Za-z0-9_]+)\\s*}}");
    private static final Pattern ANY_PLACEHOLDER = Pattern.compile("\\{\\{(.*?)}}");

    private PromptRenderer() {
    }

    /** Placeholders that are not {@code fields.<declared>} — used to reject bad templates at load time. */
    public static Set<String> invalidPlaceholders(String template, Set<String> declaredFields) {
        Set<String> invalid = new LinkedHashSet<>();
        if (template == null) return invalid;
        Matcher any = ANY_PLACEHOLDER.matcher(template);
        while (any.find()) {
            Matcher field = PLACEHOLDER.matcher(any.group(0));
            if (!field.matches() || !declaredFields.contains(field.group(1))) {
                invalid.add(any.group(0));
            }
        }
        return invalid;
    }

    public static String render(String template, Map<String, String> fields) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = flatten(fields.getOrDefault(m.group(1), ""));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Untrusted values are rendered on one line and without angle brackets, so they cannot forge prompt
     * structure (new bullet lines, headings) or close a {@code <event_data>} fence.
     */
    static String flatten(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").replace('<', '‹').replace('>', '›').strip();
    }
}

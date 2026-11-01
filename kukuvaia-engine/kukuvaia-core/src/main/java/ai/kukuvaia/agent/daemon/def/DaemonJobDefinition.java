package ai.kukuvaia.agent.daemon.def;

import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A daemon job: what runs ({@code kind} + {@code target}), on what input, under which limits.
 * Built from YAML by {@code DaemonJobLoader} or ad hoc (e.g. the legacy {@code DaemonAgentService}
 * facade).
 *
 * @param inputFields the only payload fields allowed into prompts/arguments (sanitized)
 * @param prompt      template; only {@code {{fields.x}}} placeholders are substituted
 * @param provider    explicit provider (the only way to opt in to Copilot), or {@code null}
 * @param notifications notification sink names ({@code log}, {@code webhook}, …)
 * @param source      where the definition came from (classpath file, project file, "adhoc")
 * @param inputMatch  optional filter: the run only starts if each listed field has one of the values
 *                    (case-insensitive), e.g. {@code status: [failed, error]}
 * @param args        {@code kind: mcp} only — tool arguments; string values are templates over declared
 *                    fields (rendered flattened), numbers/booleans are literal
 */
public record DaemonJobDefinition(String name, String description, JobKind kind, String target,
                                  List<String> inputFields, String prompt, List<DaemonTrigger> triggers,
                                  String provider, JobLimits limits, Overlap overlap, List<String> notifications,
                                  boolean enabled, String source, Map<String, List<String>> inputMatch,
                                  Map<String, Object> args) {

    public DaemonJobDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("job name is required");
        if (kind == null) throw new IllegalArgumentException("job '" + name + "': kind is required");
        if (target == null || target.isBlank()) throw new IllegalArgumentException("job '" + name + "': target is required");
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        notifications = notifications == null ? List.of("log") : List.copyOf(notifications);
        if (limits == null) limits = JobLimits.defaults();
        if (overlap == null) overlap = Overlap.skip();
        if (description == null) description = "";
        inputMatch = inputMatch == null ? Map.of() : Map.copyOf(inputMatch);
        args = args == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
    }

    public DaemonJobDefinition(String name, String description, JobKind kind, String target,
                               List<String> inputFields, String prompt, List<DaemonTrigger> triggers,
                               String provider, JobLimits limits, Overlap overlap, List<String> notifications,
                               boolean enabled, String source, Map<String, List<String>> inputMatch) {
        this(name, description, kind, target, inputFields, prompt, triggers, provider, limits, overlap,
                notifications, enabled, source, inputMatch, Map.of());
    }

    public DaemonJobDefinition(String name, String description, JobKind kind, String target,
                               List<String> inputFields, String prompt, List<DaemonTrigger> triggers,
                               String provider, JobLimits limits, Overlap overlap, List<String> notifications,
                               boolean enabled, String source) {
        this(name, description, kind, target, inputFields, prompt, triggers, provider, limits, overlap,
                notifications, enabled, source, Map.of());
    }

    /** Whether sanitized input fields satisfy {@link #inputMatch()} (always true without a filter). */
    public boolean matches(Map<String, String> fields) {
        for (var condition : inputMatch.entrySet()) {
            String value = fields.get(condition.getKey());
            if (value == null || condition.getValue().stream()
                    .noneMatch(v -> v.equalsIgnoreCase(value.trim()))) {
                return false;
            }
        }
        return true;
    }

    /** Ad-hoc specialist job (used by the {@code DaemonAgentService} facade). */
    public static DaemonJobDefinition adhocSpecialist(String name, String specialist, String provider, JobLimits limits) {
        return new DaemonJobDefinition(name, "", JobKind.SPECIALIST, specialist, List.of(), null,
                List.of(new DaemonTrigger.Manual()), provider, limits, Overlap.skip(), List.of("log"), true, "adhoc");
    }
}

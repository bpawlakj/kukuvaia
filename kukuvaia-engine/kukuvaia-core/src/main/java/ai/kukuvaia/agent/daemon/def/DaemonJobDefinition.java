package ai.kukuvaia.agent.daemon.def;

import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;

import java.util.List;

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
 */
public record DaemonJobDefinition(String name, String description, JobKind kind, String target,
                                  List<String> inputFields, String prompt, List<DaemonTrigger> triggers,
                                  String provider, JobLimits limits, Overlap overlap, List<String> notifications,
                                  boolean enabled, String source) {

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
    }

    /** Ad-hoc specialist job (used by the {@code DaemonAgentService} facade). */
    public static DaemonJobDefinition adhocSpecialist(String name, String specialist, String provider, JobLimits limits) {
        return new DaemonJobDefinition(name, "", JobKind.SPECIALIST, specialist, List.of(), null,
                List.of(new DaemonTrigger.Manual()), provider, limits, Overlap.skip(), List.of("log"), true, "adhoc");
    }
}

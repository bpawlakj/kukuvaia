package ai.kukuvaia.api;

import ai.kukuvaia.agent.daemon.DaemonBudgetGuard;
import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonRuntime;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.DaemonTaskStore;
import ai.kukuvaia.agent.daemon.JobRequest;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonJobRegistry;
import ai.kukuvaia.agent.daemon.def.DaemonTrigger;
import ai.kukuvaia.agent.daemon.def.PromptRenderer;
import ai.kukuvaia.security.PayloadSanitizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Daemon runtime API ({@code /api/daemon}). Authentication is enforced by {@code ApiAuthFilter} for
 * all {@code /api/*} routes. Because {@code kukuvaia.security.dev-mode=true} bypasses that filter,
 * the actions that can spend tokens or reveal full results (manual run, full task) are additionally
 * refused in dev mode unless {@code kukuvaia.daemon.api-allow-in-dev-mode=true} is set explicitly.
 */
@RestController
@RequestMapping("/api/daemon")
public class DaemonController {

    private static final Pattern JOB_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final int MAX_LIMIT = 200;

    private final DaemonJobRegistry registry;
    private final DaemonRuntime runtime;
    private final DaemonTaskStore store;
    private final DaemonBudgetGuard budget;
    private final PayloadSanitizer sanitizer;
    private final DaemonProperties properties;
    private final boolean devMode;

    public DaemonController(DaemonJobRegistry registry, DaemonRuntime runtime, DaemonTaskStore store,
                            DaemonBudgetGuard budget, PayloadSanitizer sanitizer, DaemonProperties properties,
                            @Value("${kukuvaia.security.dev-mode:false}") boolean devMode) {
        this.registry = registry;
        this.runtime = runtime;
        this.store = store;
        this.budget = budget;
        this.sanitizer = sanitizer;
        this.properties = properties;
        this.devMode = devMode;
    }

    /** Loaded job definitions and the definitions rejected at startup. */
    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        List<Map<String, Object>> jobs = registry.all().stream().map(DaemonController::describe).toList();
        return Map.of("enabled", properties.isEnabled(), "jobs", jobs, "rejected", registry.rejected());
    }

    /** Start a job with a {@code manual} trigger. Body: optional JSON whose declared input fields are used. */
    @PostMapping("/jobs/{name}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String name,
                                                   @RequestBody(required = false) String rawBody) {
        requireSensitiveAccess();
        DaemonJobDefinition job = findJob(name);
        if (job.triggers().stream().noneMatch(t -> t instanceof DaemonTrigger.Manual)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "job has no manual trigger");
        }
        Map<String, String> fields = rawBody == null || rawBody.isBlank() || job.inputFields().isEmpty()
                ? Map.of() : sanitizer.extractFields(rawBody, Set.copyOf(job.inputFields()));
        var submission = runtime.submit(job, new JobRequest(PromptRenderer.render(job.prompt(), fields),
                fields, "manual:api", null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", submission.taskId());
        body.put("status", submission.status().name());
        if (submission.reason() != null) body.put("reason", submission.reason());
        HttpStatus httpStatus = switch (submission.status()) {
            case QUEUED -> HttpStatus.ACCEPTED;
            case CANCELLED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT; // SKIPPED: overlap, budget, disabled, queue full
        };
        return ResponseEntity.status(httpStatus).body(body);
    }

    /** Recent runs (summary only — no prompt, result body or output). */
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@RequestParam(required = false) String job,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String since,
                                           @RequestParam(defaultValue = "50") int limit) {
        if (job != null && !JOB_NAME.matcher(job).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid job name");
        }
        String statusFilter = null;
        if (status != null) {
            try {
                statusFilter = DaemonTaskStatus.valueOf(status.trim().toUpperCase()).name();
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
            }
        }
        Instant sinceFilter = null;
        if (since != null) {
            try {
                sinceFilter = Instant.parse(since);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "since must be an ISO-8601 instant");
            }
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1.." + MAX_LIMIT);
        }
        return store.find(job, statusFilter, sinceFilter, limit).stream().map(DaemonController::summary).toList();
    }

    /** One run including its full result (authenticated API only — notifications carry summaries). */
    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable long id) {
        requireSensitiveAccess();
        var row = store.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such task"));
        Map<String, Object> body = summary(row);
        body.put("result", row.result());
        body.put("output", row.output());
        body.put("parentTaskId", row.parentTaskId());
        return body;
    }

    @GetMapping("/budget")
    public Map<String, Object> budget() {
        int used = budget.todayTokenUsage();
        long reserved = budget.reservedTokens();
        int daily = budget.dailyTokenBudget();
        return Map.of("dailyTokenBudget", daily, "usedToday", used, "reserved", reserved,
                "remaining", Math.max(0, daily - used - reserved),
                "runtime", Map.of("freeSlots", runtime.freeSlots(), "waiting", runtime.waitingRuns()));
    }

    private void requireSensitiveAccess() {
        if (devMode && !properties.isApiAllowInDevMode()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "disabled in dev mode (API auth is bypassed); set kukuvaia.daemon.api-allow-in-dev-mode=true for local use");
        }
    }

    private DaemonJobDefinition findJob(String name) {
        if (!JOB_NAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid job name");
        }
        return registry.find(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such job"));
    }

    private static Map<String, Object> describe(DaemonJobDefinition job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", job.name());
        m.put("description", job.description());
        m.put("kind", job.kind().dbValue());
        m.put("target", job.target());
        m.put("triggers", job.triggers().stream().map(DaemonController::trigger).toList());
        m.put("inputFields", job.inputFields());
        m.put("enabled", job.enabled());
        m.put("overlap", job.overlap().mode().name().toLowerCase() + (job.overlap().mode().name().equals("ALLOW")
                ? "(" + job.overlap().maxConcurrent() + ")" : ""));
        m.put("timeoutSeconds", job.limits().timeout().toSeconds());
        m.put("maxTokens", job.limits().maxTokens());
        m.put("notify", job.notifications());
        m.put("source", job.source());
        return m;
    }

    private static String trigger(DaemonTrigger t) {
        return switch (t) {
            case DaemonTrigger.Cron c -> "cron:" + c.expression();
            case DaemonTrigger.Webhook w -> "webhook:" + w.route();
            case DaemonTrigger.PgNotify p -> "pg-notify:" + p.channel();
            case DaemonTrigger.Manual m -> "manual";
        };
    }

    private static Map<String, Object> summary(DaemonTaskStore.TaskRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("kind", r.kind());
        m.put("target", r.target());
        m.put("trigger", r.triggerSource());
        m.put("status", r.status());
        m.put("provider", r.provider());
        m.put("model", r.model());
        m.put("tokens", Map.of("prompt", r.promptTokens(), "completion", r.completionTokens(), "charged", r.tokenUsage()));
        m.put("durationMs", r.durationMs());
        m.put("queuedAt", r.queuedAt());
        m.put("startedAt", r.startedAt());
        m.put("completedAt", r.completedAt());
        m.put("error", r.error());
        return m;
    }
}

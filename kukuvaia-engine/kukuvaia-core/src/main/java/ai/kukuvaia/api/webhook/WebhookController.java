package ai.kukuvaia.api.webhook;

import ai.kukuvaia.agent.daemon.DaemonRuntime;
import ai.kukuvaia.agent.daemon.JobRequest;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonJobRegistry;
import ai.kukuvaia.agent.daemon.def.PromptRenderer;
import ai.kukuvaia.security.PayloadSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Webhook entry point for event-driven daemon jobs: {@code POST /api/webhooks/{route}} starts the job
 * whose YAML declares {@code webhook: <route>}.
 *
 * <p>Security (AGENTS.md, in this order): {@code WebhookRateLimiter} → {@code WebhookAuthFilter}
 * (HMAC or bearer, verified before this controller runs) → field extraction here: only the job's
 * declared {@code input.fields} are read from the payload, each passed through
 * {@link PayloadSanitizer}. The raw payload never reaches a prompt — prompts are rendered from the
 * sanitized fields only.
 *
 * <p>The run is asynchronous (security-review #10 backpressure): the response is {@code 202} with the
 * task id as soon as the run is admitted; results are read from {@code /api/daemon/tasks/{id}}.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Pattern ROUTE = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final DaemonJobRegistry registry;
    private final DaemonRuntime runtime;
    private final PayloadSanitizer sanitizer;

    public WebhookController(DaemonJobRegistry registry, DaemonRuntime runtime, PayloadSanitizer sanitizer) {
        this.registry = registry;
        this.runtime = runtime;
        this.sanitizer = sanitizer;
    }

    @PostMapping("/{route}")
    public ResponseEntity<Map<String, Object>> onEvent(@PathVariable String route,
                                                       @RequestBody(required = false) String rawPayload) {
        if (!ROUTE.matcher(route).matches()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown webhook"));
        }
        DaemonJobDefinition job = registry.byWebhookRoute(route).orElse(null);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown webhook"));
        }
        String payload = rawPayload == null ? "" : rawPayload;
        log.info("Webhook '{}' received ({} bytes) for job '{}'", route, payload.length(), job.name());

        // Only declared fields, sanitized — the raw payload never reaches the LLM.
        Map<String, String> fields = job.inputFields().isEmpty()
                ? Map.of() : sanitizer.extractFields(payload, Set.copyOf(job.inputFields()));
        if (!job.matches(fields)) {
            return ResponseEntity.ok(Map.of("action", "ignored", "job", job.name(),
                    "reason", "event does not match the job's input filter"));
        }

        var submission = runtime.submit(job, new JobRequest(PromptRenderer.render(job.prompt(), fields), fields,
                "webhook:" + route, null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", submission.accepted() ? "queued" : "skipped");
        body.put("job", job.name());
        body.put("taskId", submission.taskId());
        body.put("status", submission.status().name());
        if (submission.reason() != null) body.put("reason", submission.reason());
        // 202 also for SKIPPED: the event was received and recorded; a non-2xx would make senders retry
        // into the same overlap/budget condition.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}

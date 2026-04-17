package ai.kukuvaia.api.webhook;

import ai.kukuvaia.agent.daemon.DaemonAgentService;
import ai.kukuvaia.agent.daemon.DaemonTaskResult;
import ai.kukuvaia.security.PayloadSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * Webhook endpoints for event-driven daemon tasks.
 * Security: authenticated by WebhookAuthFilter + rate-limited by WebhookRateLimiter.
 *
 * All payloads are sanitized before embedding in LLM prompts.
 * Raw payloads are NEVER passed to sub-agents.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    // Allowed fields per webhook type — everything else is dropped
    private static final Set<String> CI_FIELDS = Set.of(
            "repo", "branch", "status", "commit_sha", "author", "error_summary");
    private static final Set<String> ALERT_FIELDS = Set.of(
            "alert_name", "severity", "service", "description", "timestamp");

    private final DaemonAgentService daemonAgent;
    private final PayloadSanitizer sanitizer;

    public WebhookController(DaemonAgentService daemonAgent, PayloadSanitizer sanitizer) {
        this.daemonAgent = daemonAgent;
        this.sanitizer = sanitizer;
    }

    @PostMapping("/ci")
    public ResponseEntity<Map<String, Object>> onCiEvent(@RequestBody String rawPayload) {
        log.info("CI webhook received ({} bytes)", rawPayload.length());

        // Extract only allowed fields — raw payload never reaches LLM
        Map<String, String> fields = sanitizer.extractFields(rawPayload, CI_FIELDS);
        String status = fields.getOrDefault("status", "unknown");

        if (!"failed".equalsIgnoreCase(status) && !"error".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(Map.of("action", "ignored", "reason", "not a failure"));
        }

        String safePrompt = "CI build failed. Event data:\n" +
                sanitizer.toPromptFragment(fields) +
                "\n\nAnalyze the failure and suggest a fix.";

        DaemonTaskResult result = daemonAgent.execute(
                "ci-failure-analysis", "analyst", safePrompt, "webhook:ci", null);

        return ResponseEntity.ok(Map.of(
                "action", "analyzed",
                "taskId", result.taskId(),
                "status", result.status().name()
        ));
    }

    @PostMapping("/alert")
    public ResponseEntity<Map<String, Object>> onAlert(@RequestBody String rawPayload) {
        log.info("Alert webhook received ({} bytes)", rawPayload.length());

        Map<String, String> fields = sanitizer.extractFields(rawPayload, ALERT_FIELDS);

        String safePrompt = "Monitoring alert received. Event data:\n" +
                sanitizer.toPromptFragment(fields) +
                "\n\nInvestigate using available tools and report findings.";

        DaemonTaskResult result = daemonAgent.execute(
                "alert-investigation", "devops", safePrompt, "webhook:alert", null);

        return ResponseEntity.ok(Map.of(
                "action", "investigated",
                "taskId", result.taskId(),
                "status", result.status().name()
        ));
    }
}

package ai.kukuvaia.api;

import ai.kukuvaia.agentrun.AgentRun;
import ai.kukuvaia.agentrun.AgentRunService;
import ai.kukuvaia.agentrun.AgentRunSpec;
import ai.kukuvaia.agentrun.AgentRunStatus;
import ai.kukuvaia.agentrun.LlmFollowupSpec;
import ai.kukuvaia.agentrun.ModelPreferenceSpec;
import ai.kukuvaia.agentrun.SimilarityContextSpec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP entry point for non-interactive agent runs (P23 §Phase E thin slice).
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@code POST /api/agent-runs} — async; returns 202 + {@code runId}; caller polls.</li>
 *   <li>{@code POST /api/agent-runs/run} — sync; blocks until terminal; returns final {@link AgentRun}.</li>
 *   <li>{@code GET /api/agent-runs/{id}} — fetch a run by id.</li>
 *   <li>{@code GET /api/agent-runs?invokerName=…} — list recent runs for an invoker.</li>
 * </ul>
 *
 * <p>Auth: inherits the existing {@code SecurityFilterChain} that already applies to
 * {@code /api/**} (same as {@code /api/chat}). No new scope is introduced in v1.
 */
@RestController
@RequestMapping("/api/agent-runs")
public class AgentRunController {

    private static final Logger log = LoggerFactory.getLogger(AgentRunController.class);

    private final AgentRunService service;

    public AgentRunController(AgentRunService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EnqueueResponse> enqueue(@RequestBody AgentRunRequest request) {
        AgentRunSpec spec = request.toSpec();
        UUID id = service.enqueue(spec);
        log.info("agent_run enqueued id={} invoker={}/{}", id, spec.invokerKind(), spec.invokerName());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new EnqueueResponse(id, AgentRunStatus.QUEUED.wireValue()));
    }

    @PostMapping(value = "/run", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentRunView> runSync(@RequestBody AgentRunRequest request) {
        AgentRunSpec spec = request.toSpec();
        AgentRun run = service.runSync(spec);
        log.info("agent_run completed id={} status={} model={} tokens={}",
                run.id(), run.status(), run.model(), run.totalTokens());
        return ResponseEntity.ok(AgentRunView.from(run));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentRunView> getOne(@PathVariable UUID id) {
        return service.findById(id)
                .map(run -> ResponseEntity.ok(AgentRunView.from(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AgentRunView>> list(
            @RequestParam("invokerName") String invokerName,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (invokerName == null || invokerName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int clamped = Math.max(1, Math.min(limit, 200));
        List<AgentRun> runs = service.findByInvokerName(invokerName, clamped);
        return ResponseEntity.ok(runs.stream().map(AgentRunView::from).toList());
    }

    // --- DTOs -----------------------------------------------------------

    /**
     * Wire shape for POST. Mirrors {@link AgentRunSpec} with simpler scalar shapes for the
     * nested specs so JSON callers don't need Java-record-aware shapes.
     */
    public record AgentRunRequest(
            String invokerName,
            String invokerKind,
            String inputType,
            JsonNode input,
            String personaName,
            String taskClass,
            UUID parentRunId,
            UUID threadId,
            List<String> tags,
            String severity,
            LlmFollowupRequest llmFollowup) {

        AgentRunSpec toSpec() {
            return new AgentRunSpec(
                    invokerName,
                    invokerKind,
                    inputType,
                    input == null ? JsonNodeFactory.instance.objectNode() : input,
                    personaName,
                    llmFollowup == null ? null : llmFollowup.toSpec(),
                    parentRunId,
                    threadId,
                    tags,
                    severity,
                    taskClass);
        }
    }

    public record LlmFollowupRequest(
            String systemPrompt,
            String userPromptTemplate,
            Boolean skipIfEmpty,
            Boolean storeEmbedding,
            Integer maxOutputTokens,
            Boolean promptCaching,
            SimilarityContextRequest similarityContext,
            ModelPreferenceRequest modelPreference) {

        LlmFollowupSpec toSpec() {
            return new LlmFollowupSpec(
                    systemPrompt,
                    userPromptTemplate,
                    skipIfEmpty == null || skipIfEmpty,
                    storeEmbedding == null || storeEmbedding,
                    maxOutputTokens,
                    promptCaching != null && promptCaching,
                    similarityContext == null ? SimilarityContextSpec.disabled() : similarityContext.toSpec(),
                    modelPreference == null ? null : modelPreference.toSpec());
        }
    }

    public record SimilarityContextRequest(
            Boolean enabled, Integer topK, Integer maxAgeDays,
            Boolean filterByInvokerName, List<String> filterByTags) {

        SimilarityContextSpec toSpec() {
            return new SimilarityContextSpec(
                    enabled != null && enabled,
                    topK == null ? 3 : topK,
                    maxAgeDays,
                    filterByInvokerName == null || filterByInvokerName,
                    filterByTags);
        }
    }

    public record ModelPreferenceRequest(String primary, String escalateTo, String escalationTrigger) {
        ModelPreferenceSpec toSpec() {
            return new ModelPreferenceSpec(primary, escalateTo, escalationTrigger);
        }
    }

    public record EnqueueResponse(UUID runId, String status) {}

    /**
     * Outbound view of an agent run. Embedding is intentionally omitted (large; not useful to
     * external callers). Null fields are dropped from JSON via {@link JsonInclude.Include#NON_NULL}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentRunView(
            UUID id,
            UUID threadId,
            UUID parentRunId,
            String invokerName,
            String invokerKind,
            String inputType,
            JsonNode input,
            String instructions,
            String personaName,
            String taskClass,
            String model,
            String status,
            String errorCode,
            String errorMessage,
            JsonNode output,
            String outputText,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String queuedAt,
            String startedAt,
            String completedAt,
            String createdAt,
            List<String> tags,
            String severity) {

        static AgentRunView from(AgentRun r) {
            return new AgentRunView(
                    r.id(), r.threadId(), r.parentRunId(),
                    r.invokerName(), r.invokerKind(), r.inputType(),
                    r.input(), r.instructions(), r.personaName(), r.taskClass(),
                    r.model(), r.status() != null ? r.status().wireValue() : null,
                    r.errorCode(), r.errorMessage(),
                    r.output(), r.outputText(),
                    r.promptTokens(), r.completionTokens(), r.totalTokens(),
                    r.queuedAt() != null ? r.queuedAt().toString() : null,
                    r.startedAt() != null ? r.startedAt().toString() : null,
                    r.completedAt() != null ? r.completedAt().toString() : null,
                    r.createdAt() != null ? r.createdAt().toString() : null,
                    r.tags(), r.severity());
        }
    }

    /** Surface IllegalArgumentException as 400 (e.g. unknown persona, missing required field). */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

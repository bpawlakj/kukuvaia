# P01: Observability -- OpenTelemetry Traces + Micrometer Metrics

## Problem

kukuvaia-engine has zero observability infrastructure. Every LLM call, tool invocation, and memory operation is a black box once deployed. The only insight is SLF4J text logs from `ProviderAuditLog` (provider, model, token counts) and `TokenBudgetAdvisor` (per-session usage). There are no distributed traces, no Prometheus-scrapeable metrics, no dashboards, no alerting hooks, and no way to correlate a slow user-facing response to a specific advisor or tool call in the chain.

When a chat request takes 30 seconds, the operator currently has no way to know whether the bottleneck was the LLM provider (network latency, queuing), a tool call (slow SQL query, external API), memory extraction (embedding computation, LLM summarization), or the advisor chain itself.

## Current State

| Component | Observability today | Gap |
|-----------|-------------------|-----|
| `ProviderAuditLog` | Logs provider/model/tokens to SLF4J with MDC keys `provider`, `executionContext`, `specialist` | No metrics, no traces, no Prometheus |
| `TokenBudgetAdvisor` | In-memory `ConcurrentHashMap<String, AtomicInteger>` per session | Not exposed as metric, lost on restart |
| `SmartMemoryAdvisor` | `log.info("Memory: injected {} for user {}")` | No duration metric, no trace span |
| `MemoryExtractionService` | `log.info("Extracted {} memories")` | No duration metric, no trace span |
| `SubAgentFactory` | `log.info("Creating sub-agent...")` | No sub-agent duration metric |
| `DaemonAgentService` | `duration_ms` in daemon_tasks table | Not exposed as Prometheus metric |
| Spring Boot Actuator | Not in dependencies | Missing entirely |
| Prometheus | Not in dependencies | Missing entirely |
| OpenTelemetry | Not in dependencies | Missing entirely |

### Key files involved

- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/ProviderAuditLog.java` -- advisor, logs MDC
- `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/TokenBudgetAdvisor.java` -- tracks tokens in-memory
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java` -- chat orchestration
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonAgentService.java` -- daemon tasks
- `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/advisor/SmartMemoryAdvisor.java` -- memory injection
- `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/extraction/MemoryExtractionService.java` -- extraction
- `kukuvaia-app/src/main/resources/application.yaml` -- Spring configuration
- `kukuvaia-app/build.gradle` -- app dependencies
- `kukuvaia-core/build.gradle` -- core dependencies

## Architecture

```
                          Grafana (dashboards)
                              |
                         Prometheus  <----  /actuator/prometheus
                              |                    |
                    scrape every 15s         kukuvaia-engine
                                                   |
                                     +-------------+-------------+
                                     |             |             |
                              Micrometer     OTel Agent    Custom Metrics
                              (auto)         (traces)      (manual)
                                     |             |             |
                              JVM, HTTP,    advisor spans   llm_request_*
                              HikariCP      tool spans      tool_call_*
                              caches        memory spans    memory_*
                                                            session_*

Trace flow for a single chat request:
+------------------------------------------------------------------+
| POST /api/chat (HTTP span - auto-instrumented)                   |
|  +--------------------------------------------------------------+|
|  | AgentService.streamChat (custom span)                        ||
|  |  +----------------------------------------------------------+||
|  |  | ProviderAuditLog.before/after (advisor span)             |||
|  |  +----------------------------------------------------------+||
|  |  | SmartMemoryAdvisor.before (advisor span)                 |||
|  |  |  +------------------------------------------------------+|||
|  |  |  | EmbeddingService.findSimilar (memory span)           ||||
|  |  |  +------------------------------------------------------+|||
|  |  +----------------------------------------------------------+||
|  |  | ChatModel.call (LLM span - auto or manual)              |||
|  |  +----------------------------------------------------------+||
|  |  | ToolCallAdvisor (tool loop span - per tool call)         |||
|  |  |  +------------------------------------------------------+|||
|  |  |  | @McpTool execution (tool span)                       ||||
|  |  |  +------------------------------------------------------+|||
|  |  +----------------------------------------------------------+||
|  +--------------------------------------------------------------+|
|  | MemoryExtractionService.extract (async span, linked)         ||
|  +--------------------------------------------------------------+|
+------------------------------------------------------------------+
```

### Metric naming convention

All custom metrics use the `kukuvaia.` prefix per Micrometer best practices:

| Metric name | Type | Tags | Source |
|-------------|------|------|--------|
| `kukuvaia.llm.request.duration` | Timer | `provider`, `model`, `context`, `specialist`, `status` | ProviderAuditLog |
| `kukuvaia.llm.tokens.total` | Counter | `provider`, `model`, `context`, `type` (prompt/completion) | ProviderAuditLog |
| `kukuvaia.llm.tokens.session` | Gauge | `sessionId` | TokenBudgetAdvisor |
| `kukuvaia.tool.call.duration` | Timer | `tool`, `status` | ToolCallMetrics (new) |
| `kukuvaia.memory.injection.duration` | Timer | `userId`, `strategy` (semantic/fallback) | SmartMemoryAdvisor |
| `kukuvaia.memory.injection.count` | Counter | `userId`, `category` | SmartMemoryAdvisor |
| `kukuvaia.memory.extraction.duration` | Timer | `sessionId`, `status` | MemoryExtractionService |
| `kukuvaia.memory.extraction.facts` | Counter | `category` | MemoryExtractionService |
| `kukuvaia.session.active` | Gauge | -- | SessionMetrics (new) |
| `kukuvaia.daemon.task.duration` | Timer | `task`, `specialist`, `status` | DaemonAgentService |
| `kukuvaia.daemon.task.total` | Counter | `task`, `status` | DaemonAgentService |
| `kukuvaia.subagent.duration` | Timer | `specialist`, `provider`, `context` | SubAgentFactory |

## Implementation

### Step 1: Add dependencies

**`kukuvaia-app/build.gradle`:**

```groovy
dependencies {
    implementation project(':kukuvaia-core')
    implementation project(':kukuvaia-agents')

    // Observability
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

**`kukuvaia-core/build.gradle`:**

```groovy
dependencies {
    // (existing deps...)

    // Micrometer API (for custom metrics in domain code)
    api 'io.micrometer:micrometer-core'
}
```

**`kukuvaia-memory/build.gradle`:**

```groovy
dependencies {
    // (existing deps...)

    // Micrometer API (for metrics in memory advisors)
    api 'io.micrometer:micrometer-core'
}
```

The `micrometer-core` dependency is already transitively available via `spring-boot-starter-web`, but declaring it explicitly in modules that use `MeterRegistry` makes the dependency visible.

### Step 2: Configure Actuator + Prometheus endpoint

**`kukuvaia-app/src/main/resources/application.yaml` -- add to existing config:**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized
    prometheus:
      enabled: true
  metrics:
    tags:
      application: kukuvaia-engine
    distribution:
      percentiles-histogram:
        kukuvaia.llm.request.duration: true
        kukuvaia.tool.call.duration: true
        kukuvaia.memory.extraction.duration: true
      slo:
        kukuvaia.llm.request.duration: 1s,5s,15s,30s,60s
```

### Step 3: Instrument ProviderAuditLog with Micrometer

**`kukuvaia-core/src/main/java/ai/kukuvaia/provider/ProviderAuditLog.java`:**

Inject `MeterRegistry` via constructor. Start a `Timer.Sample` in `before()`, stop it in `after()` with tags derived from MDC/context. Record token counters.

```java
@Component
public class ProviderAuditLog implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ProviderAuditLog.class);
    private static final String CTX_PROVIDER = "kukuvaia.provider";
    private static final String CTX_CONTEXT = "kukuvaia.executionContext";
    private static final String CTX_SPECIALIST = "kukuvaia.specialist";
    private static final String CTX_TIMER_SAMPLE = "kukuvaia.timerSample";

    private final MeterRegistry meterRegistry;

    public ProviderAuditLog(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String provider = (String) request.context().getOrDefault(CTX_PROVIDER, "unknown");
        String executionContext = (String) request.context().getOrDefault(CTX_CONTEXT, "interactive");
        String specialist = (String) request.context().getOrDefault(CTX_SPECIALIST, "parent");

        MDC.put("provider", provider);
        MDC.put("executionContext", executionContext);
        MDC.put("specialist", specialist);

        log.info("LLM call: provider={}, context={}, specialist={}, messageCount={}",
                provider, executionContext, specialist,
                request.prompt().getInstructions().size());

        // Start timer
        Timer.Sample sample = Timer.start(meterRegistry);

        return request.mutate()
                .context(Map.of(
                        CTX_PROVIDER, provider,
                        CTX_CONTEXT, executionContext,
                        CTX_SPECIALIST, specialist,
                        CTX_TIMER_SAMPLE, sample
                ))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String provider = (String) response.request().context().getOrDefault(CTX_PROVIDER, "unknown");
        String context = (String) response.request().context().getOrDefault(CTX_CONTEXT, "interactive");
        String specialist = (String) response.request().context().getOrDefault(CTX_SPECIALIST, "parent");

        // Stop timer
        Object sampleObj = response.request().context().get(CTX_TIMER_SAMPLE);
        if (sampleObj instanceof Timer.Sample sample) {
            sample.stop(Timer.builder("kukuvaia.llm.request.duration")
                    .tag("provider", provider)
                    .tag("context", context)
                    .tag("specialist", specialist)
                    .tag("status", "success")
                    .register(meterRegistry));
        }

        // Record token usage
        var usage = response.chatResponse().getMetadata().getUsage();
        if (usage != null) {
            Counter.builder("kukuvaia.llm.tokens.total")
                    .tag("provider", provider)
                    .tag("type", "prompt")
                    .register(meterRegistry)
                    .increment(usage.getPromptTokens());
            Counter.builder("kukuvaia.llm.tokens.total")
                    .tag("provider", provider)
                    .tag("type", "completion")
                    .register(meterRegistry)
                    .increment(usage.getCompletionTokens());

            log.info("LLM response: promptTokens={}, completionTokens={}, totalTokens={}",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }

        MDC.remove("provider");
        MDC.remove("executionContext");
        MDC.remove("specialist");
        return response;
    }
}
```

### Step 4: Instrument SmartMemoryAdvisor

**`kukuvaia-memory/src/main/java/ai/kukuvaia/memory/advisor/SmartMemoryAdvisor.java`:**

Add `MeterRegistry` to constructor. Wrap the `before()` retrieval logic in a `Timer.Sample`. Record a counter for injected memory count by category.

```java
@Component
public class SmartMemoryAdvisor implements BaseAdvisor {

    private final SmartMemoryRepository repository;
    private final EmbeddingService embeddingService;
    private final MeterRegistry meterRegistry;

    public SmartMemoryAdvisor(SmartMemoryRepository repository,
                              EmbeddingService embeddingService,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String strategy = "fallback";

        try {
            // ... existing retrieval logic ...
            // set strategy = "semantic" when embedding path is used

            return augmentedRequest;
        } finally {
            sample.stop(Timer.builder("kukuvaia.memory.injection.duration")
                    .tag("strategy", strategy)
                    .register(meterRegistry));
        }
    }
}
```

### Step 5: Instrument MemoryExtractionService

**`kukuvaia-memory/src/main/java/ai/kukuvaia/memory/extraction/MemoryExtractionService.java`:**

Add `MeterRegistry` to constructor. Wrap `extract()` in a timer. Count extracted facts by category.

```java
public void extract(String userId, String sessionId) {
    Timer.Sample sample = Timer.start(meterRegistry);
    String status = "success";
    try {
        // ... existing extraction logic ...

        for (var fact : facts) {
            saveFact(userId, sessionId, fact);
            Counter.builder("kukuvaia.memory.extraction.facts")
                    .tag("category", fact.getOrDefault("category", "unknown"))
                    .register(meterRegistry)
                    .increment();
        }

    } catch (Exception e) {
        status = "error";
        log.warn("Memory extraction failed for session {}: {}", sessionId, e.getMessage());
    } finally {
        sample.stop(Timer.builder("kukuvaia.memory.extraction.duration")
                .tag("status", status)
                .register(meterRegistry));
    }
}
```

### Step 6: Instrument DaemonAgentService

**`kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonAgentService.java`:**

Add `MeterRegistry`. Record `kukuvaia.daemon.task.duration` timer and `kukuvaia.daemon.task.total` counter in the `execute()` method.

```java
// After successful execution:
Timer.builder("kukuvaia.daemon.task.duration")
        .tag("task", taskName)
        .tag("specialist", specialistType)
        .tag("status", "completed")
        .register(meterRegistry)
        .record(Duration.ofMillis(durationMs));

Counter.builder("kukuvaia.daemon.task.total")
        .tag("task", taskName)
        .tag("status", "completed")
        .register(meterRegistry)
        .increment();
```

### Step 7: Create ToolCallMetrics advisor (new file)

**`kukuvaia-core/src/main/java/ai/kukuvaia/advisors/ToolCallMetrics.java`:**

A lightweight advisor that records tool call durations. Placed in the advisor chain after `ToolCallAdvisor`. Since Spring AI's `ToolCallAdvisor` handles tool calls internally, an alternative approach is to wrap tool callbacks via a `MeterBinder` or use AOP on `@McpTool` methods.

Recommended approach: use Micrometer's `@Timed` with AOP on tool methods.

```java
package ai.kukuvaia.advisors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Times all @Tool method executions and records them as Micrometer metrics.
 * Provides per-tool-name duration histograms and call counts.
 */
@Aspect
@Component
public class ToolCallMetrics {

    private final MeterRegistry meterRegistry;

    public ToolCallMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object timeToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String toolName = joinPoint.getSignature().getName();
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            status = "error";
            throw t;
        } finally {
            sample.stop(Timer.builder("kukuvaia.tool.call.duration")
                    .tag("tool", toolName)
                    .tag("status", status)
                    .register(meterRegistry));
        }
    }
}
```

Note: Requires `spring-boot-starter-aop` dependency in `kukuvaia-core/build.gradle`:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-aop'
```

### Step 8: Session active gauge

**`kukuvaia-core/src/main/java/ai/kukuvaia/api/SessionMetrics.java`:**

```java
package ai.kukuvaia.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Exposes active session count as a Micrometer gauge.
 */
@Component
public class SessionMetrics {

    public SessionMetrics(MeterRegistry meterRegistry, JdbcTemplate jdbc) {
        Gauge.builder("kukuvaia.session.active", jdbc,
                j -> j.queryForObject(
                        "SELECT count(*) FROM kukuvaia.sessions WHERE status = 'active'",
                        Integer.class))
                .description("Number of active sessions")
                .register(meterRegistry);
    }
}
```

### Step 9: OpenTelemetry auto-instrumentation (optional, zero-code)

OpenTelemetry Java agent provides distributed tracing without code changes. Attach it as a JVM agent at startup:

```bash
# Download agent (one-time)
curl -Lo opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

# Run with agent
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=kukuvaia-engine \
     -Dotel.traces.exporter=otlp \
     -Dotel.metrics.exporter=prometheus \
     -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
     -jar kukuvaia-app/build/libs/kukuvaia-app-0.1.0-SNAPSHOT.jar
```

This automatically instruments:
- HTTP server spans (Spring MVC / Tomcat)
- HTTP client spans (Spring WebClient / RestTemplate)
- JDBC query spans (PostgreSQL driver)
- SLF4J log correlation (trace ID in MDC)

Custom spans from the Micrometer `Timer` instances in Steps 3-8 are automatically bridged to OTel spans via the micrometer-otel bridge (included in the OTel agent).

Alternative: for a lightweight setup without an agent, add the Spring Boot OTel starter:

```groovy
// kukuvaia-app/build.gradle (alternative to javaagent)
implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.12.0'
```

### Step 10: Grafana dashboard suggestions

Create provisioned Grafana dashboards or use Grafana JSON import. Key panels:

**LLM Performance Dashboard:**
1. LLM request latency (p50, p95, p99) by provider -- `histogram_quantile(0.95, rate(kukuvaia_llm_request_duration_seconds_bucket[5m]))`
2. Token consumption rate by provider -- `rate(kukuvaia_llm_tokens_total[1h])`
3. Requests per minute by context (interactive vs daemon) -- `rate(kukuvaia_llm_request_duration_seconds_count[5m]) * 60`
4. Error rate by provider -- filter by `status="error"`

**Tool Performance Dashboard:**
1. Tool call latency by tool name -- `kukuvaia_tool_call_duration_seconds`
2. Tool call error rate -- `rate(... {status="error"}[5m])`
3. Top 10 slowest tools (table)

**Memory Dashboard:**
1. Memory injection latency -- `kukuvaia_memory_injection_duration_seconds`
2. Memory extraction latency -- `kukuvaia_memory_extraction_duration_seconds`
3. Extracted facts per hour by category -- `rate(kukuvaia_memory_extraction_facts_total[1h])`

**System Dashboard:**
1. Active sessions gauge -- `kukuvaia_session_active`
2. JVM heap usage -- `jvm_memory_used_bytes` (auto from Actuator)
3. HikariCP connection pool -- `hikaricp_connections_active` (auto from Actuator)
4. HTTP request rate and latency -- `http_server_requests_seconds` (auto from Actuator)

## Configuration

### application.yaml additions (full block)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized
    prometheus:
      enabled: true
  metrics:
    tags:
      application: kukuvaia-engine
    distribution:
      percentiles-histogram:
        kukuvaia.llm.request.duration: true
        kukuvaia.tool.call.duration: true
        kukuvaia.memory.extraction.duration: true
        kukuvaia.memory.injection.duration: true
      slo:
        kukuvaia.llm.request.duration: 1s,5s,15s,30s,60s
        kukuvaia.tool.call.duration: 100ms,500ms,1s,5s
    enable:
      jvm: true
      process: true
      system: true
      hikaricp: true
      jdbc: true
```

### Environment variables for OpenTelemetry (optional)

```bash
OTEL_SERVICE_NAME=kukuvaia-engine
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=prometheus
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_LOGS_EXPORTER=none  # keep SLF4J for now
```

## Dependencies

### New Gradle dependencies

| Dependency | Module | Purpose |
|-----------|--------|---------|
| `spring-boot-starter-actuator` | kukuvaia-app | Actuator endpoints, health, info |
| `micrometer-registry-prometheus` | kukuvaia-app | Prometheus scrape endpoint |
| `micrometer-core` | kukuvaia-core, kukuvaia-memory | MeterRegistry API for custom metrics |
| `spring-boot-starter-aop` | kukuvaia-core | AOP for @Tool method timing |
| `opentelemetry-javaagent` (runtime, optional) | -- | Distributed tracing (JVM agent, not Gradle dep) |

### Version management

All Spring Boot and Micrometer versions are managed by the existing BOM (`spring-boot-dependencies:3.4.4`). No explicit version pinning needed.

## Verification

### 1. Build succeeds

```bash
cd kukuvaia-engine
./gradlew clean build
```

### 2. Actuator health endpoint responds

```bash
./gradlew :kukuvaia-app:bootRun &
curl -s http://localhost:8080/actuator/health | jq .
# Expected: {"status":"UP","components":{...}}
```

### 3. Prometheus endpoint exposes custom metrics

```bash
curl -s http://localhost:8080/actuator/prometheus | grep kukuvaia
# Expected lines:
# kukuvaia_llm_request_duration_seconds_count{...}
# kukuvaia_llm_tokens_total_total{...}
# kukuvaia_session_active ...
```

### 4. Send a chat and verify metrics increment

```bash
# Send a chat message
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-obs-1","message":"Hello"}'

# Check metrics updated
curl -s http://localhost:8080/actuator/prometheus | grep kukuvaia_llm_request_duration
# Should show count > 0
```

### 5. Unit tests pass with MeterRegistry injected

Existing tests for `ProviderAuditLog`, `TokenBudgetAdvisor`, `SmartMemoryAdvisor` must be updated to inject a `SimpleMeterRegistry` (from `micrometer-test`). This is a no-op registry that discards metrics but satisfies constructor requirements.

```java
@BeforeEach
void setUp() {
    MeterRegistry registry = new SimpleMeterRegistry();
    providerAuditLog = new ProviderAuditLog(registry);
}
```

### 6. Tool AOP timing works

```bash
curl -s http://localhost:8080/actuator/prometheus | grep kukuvaia_tool_call
# After a tool-using chat: kukuvaia_tool_call_duration_seconds_count{tool="save_memory",...} > 0
```

## Effort Estimate

| Task | Effort | Notes |
|------|--------|-------|
| Add dependencies (Steps 1-2) | 0.5h | Gradle + YAML config |
| Instrument ProviderAuditLog (Step 3) | 1h | Timer + Counter, update tests |
| Instrument SmartMemoryAdvisor (Step 4) | 0.5h | Timer, update tests |
| Instrument MemoryExtractionService (Step 5) | 0.5h | Timer + Counter |
| Instrument DaemonAgentService (Step 6) | 0.5h | Timer + Counter |
| Create ToolCallMetrics AOP (Step 7) | 1h | New file + AOP config + test |
| Create SessionMetrics (Step 8) | 0.5h | New file + test |
| OTel agent setup (Step 9) | 1h | Documentation + startup script |
| Grafana dashboard JSON (Step 10) | 2h | 4 dashboards, optional |
| Test updates (all modules) | 1h | Inject SimpleMeterRegistry |
| **Total** | **~8h** | |

## Koog-Inspired Enhancements (2026-04-15)

After evaluating Koog AI's observability model (see `docs/analyzes/koog-ai-evaluation.md`), two enhancements are added to P01. Koog's key insight: **per-agent-run hierarchical spans** with conversation-level trace IDs are where the real debugging value lies.

### Enhancement A: Session-scoped span attributes

Add `kukuvaia.*` OTel attributes to every span so you can query "all traces for session X" in Jaeger/Grafana Tempo.

**File:** `kukuvaia-core/src/main/java/ai/kukuvaia/provider/ProviderAuditLog.java`
**Change:** In `before()`, after existing MDC puts, add:

```java
import io.opentelemetry.api.trace.Span;

// In before(), after MDC puts:
Span currentSpan = Span.current();
currentSpan.setAttribute("kukuvaia.session.id", sessionId);
currentSpan.setAttribute("kukuvaia.provider", provider);
currentSpan.setAttribute("kukuvaia.specialist", specialist);
currentSpan.setAttribute("kukuvaia.execution.context", executionContext);
```

**File:** `kukuvaia-core/build.gradle`
**Change:** Add:

```groovy
compileOnly 'io.opentelemetry:opentelemetry-api'
```

Safe: `Span.current()` returns no-op when OTel agent not attached. Zero overhead in non-instrumented deployments.

### Enhancement B: SubAgent context propagation

When SubAgentFactory spawns a CompletableFuture on the worker executor, the OTel context is lost. Fix: capture parent context before async dispatch, restore inside the task, wrap sub-agent work in a named span.

**File:** `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java`
**Changes in `execute()` and `executeWithModel()`:**

```java
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

// Inject Tracer via constructor (no-op when OTel not present)
private final Tracer tracer;

// In execute():
Context otelContext = Context.current(); // capture parent

CompletableFuture.supplyAsync(() -> {
    try (Scope scope = otelContext.makeCurrent()) { // restore parent
        Span span = tracer.spanBuilder("kukuvaia.subagent." + specialistType)
                .setAttribute("kukuvaia.specialist", specialistType)
                .setAttribute("kukuvaia.subagent.depth", currentDepth)
                .startSpan();
        try (Scope inner = span.makeCurrent()) {
            return subAgent.prompt()...call().content();
        } finally {
            span.end();
        }
    }
}, workerExecutor);
```

**File:** `kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonAgentService.java`
**Change:** Same pattern — propagate OTel context around `subAgentFactory.execute()`.

**Additional dependency in `kukuvaia-core/build.gradle`:**

```groovy
compileOnly 'io.opentelemetry:opentelemetry-context'
```

### Resulting span hierarchy

```
POST /api/chat (HTTP span, auto-instrumented by OTel agent)
  └─ AgentService.streamChat (custom span)
       ├─ ProviderAuditLog (advisor span, attrs: session.id, provider)
       ├─ SmartMemoryAdvisor (advisor span, timer)
       │    └─ EmbeddingService.findSimilar (memory span)
       ├─ ChatModel.call (LLM span, auto by OTel agent)
       ├─ ToolCallAdvisor loop (tool spans, AOP-timed)
       │    └─ @McpTool execution (tool span per tool)
       ├─ kukuvaia.subagent.analyst (Enhancement B span)
       │    ├─ ChatModel.call (child LLM span)
       │    └─ @McpTool execution (child tool span)
       └─ MemoryExtractionService.extract (async linked span)
```

### Updated effort estimate

| Task | Effort |
|------|--------|
| P01 Steps 1-10 (original) | ~8h |
| Enhancement A: span attributes | ~0.5h |
| Enhancement B: SubAgent context propagation | ~1.5h |
| **Total** | **~10h** |

---

## SpanEventBlock Extension — SSE Streaming for Real-Time Clients (2026-04-16)

**Rationale**: Enhancements A and B give us rich OTel spans for backend observability (Jaeger / Grafana Tempo). The same data is valuable to **interactive clients** (CLI, future web UI) that want to show *"what the agent is doing right now"* with multi-role hierarchy, live token counts, and sub-action lists.

Rather than invent a parallel tool-lifecycle protocol, we expose OTel spans directly over the existing SSE stream as a new `OutputBlock` subtype. Single source of truth: one span model powers both Jaeger traces and live UI.

**Consumer**: P01.1 (CLI Activity Tracker) builds a live span tree from this stream.

### Extension Step A: Add `SpanEventBlock` to OutputBlock hierarchy

**File:** `kukuvaia-core/src/main/java/ai/kukuvaia/output/block/SpanEventBlock.java`

```java
package ai.kukuvaia.output.block;

import java.time.Instant;
import java.util.Map;

/**
 * Streams OTel span lifecycle events over SSE to interactive clients.
 * Sealed-type member of OutputBlock hierarchy. Phase values: "start" | "end" | "delta".
 */
public record SpanEventBlock(
    String spanId,
    String parentSpanId,      // empty string = top-level
    String name,              // "tool:<name>" | "role:<specialist>" | "llm:<provider>" | "memory:<op>"
    String phase,             // "start" | "end" | "delta"
    Map<String, Object> attributes,   // tokens, summary, status, input preview, ...
    Instant timestamp
) implements OutputBlock {}
```

Sealed-type `permits` clause in `OutputBlock.java` extended to include `SpanEventBlock`.

### Extension Step B: `SpanEventEmittingAdvisor`

**File:** `kukuvaia-core/src/main/java/ai/kukuvaia/advisor/SpanEventEmittingAdvisor.java`

An advisor that subscribes to OTel span lifecycle (via `SpanProcessor`) and publishes `SpanEventBlock` into the active request's SSE sink.

```java
@Component
public class SpanEventEmittingAdvisor implements SpanProcessor {

    private final SseEmitterRegistry sseRegistry;

    public SpanEventEmittingAdvisor(SseEmitterRegistry sseRegistry) {
        this.sseRegistry = sseRegistry;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String sessionId = (String) span.getAttribute(AttributeKey.stringKey("kukuvaia.session.id"));
        if (sessionId == null) return;   // only forward kukuvaia-tagged spans

        sseRegistry.emit(sessionId, new SpanEventBlock(
            span.getSpanContext().getSpanId(),
            span.getParentSpanContext().isValid() ? span.getParentSpanContext().getSpanId() : "",
            span.getName(),
            "start",
            toMap(span.getAttributes()),
            Instant.now()
        ));
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // emit "end" with final attributes (tokens, result summary, status)
    }

    // Optional: token deltas emitted via StreamingResponseHandler → "delta" phase
}
```

Register via OTel `SdkTracerProvider.builder().addSpanProcessor(spanEventEmittingAdvisor)`.

**Thread safety requirement**: `SpanProcessor.onStart/onEnd` callbacks run on arbitrary threads (including parallel `CompletableFuture` worker executors used by `SubAgentFactory`). `SseEmitterRegistry.emit()` MUST serialize emissions per session — either a per-session lock/synchronized block, or a `BlockingQueue` drained by a single emitter thread. Spring's default `SseEmitter.send()` is **not** thread-safe without external synchronization. Test: two parallel sub-agents emitting spans concurrently must produce well-ordered SSE output (no interleaved JSON fragments).

### Extension Step C: Span naming convention

Spans emitted by kukuvaia follow a `<category>:<subject>` convention so clients can categorize without inspecting attributes:

| Prefix | Meaning | Example |
|--------|---------|---------|
| `tool:` | Leaf tool call | `tool:grep`, `tool:read_file`, `tool:bash_run` |
| `role:` | Sub-agent branch | `role:worker/research`, `role:daemon/nightly` |
| `llm:` | LLM provider call | `llm:openai`, `llm:copilot` |
| `memory:` | Memory operation | `memory:inject`, `memory:extract` |

Required span attributes (subset):

| Attribute | Type | Set when |
|-----------|------|----------|
| `kukuvaia.session.id` | string | Always (gates SSE emission) |
| `kukuvaia.tokens.input` | long | On `llm:*` end |
| `kukuvaia.tokens.output` | long | On `llm:*` end |
| `kukuvaia.tokens.delta` | long | On `delta` phase (incremental tokens since last event on this span) |
| `kukuvaia.summary` | string | On `tool:*` end (short human-readable result) |
| `kukuvaia.status` | string | On any end (`"success"` / `"error"` / `"cancelled"`) |
| `kukuvaia.error.type` | string | On end when `status="error"` (exception class name) |
| `kukuvaia.error.message` | string | On end when `status="error"` (truncated to 200 chars, PII-sanitized) |
| `kukuvaia.specialist` | string | On `role:*` start |

Span name + attributes replace what would have been custom `ToolStartBlock` / `ToolCompleteBlock` / `TokenDeltaBlock` types. One model, all use cases.

### Phase semantics

| Phase | Purpose | Attributes carried |
|-------|---------|---------------------|
| `start` | Span began. CLI creates node, attaches to parent. | All start-time attributes (e.g. `kukuvaia.specialist` for roles, `input_preview` for tools) |
| `delta` | Incremental update during streaming (LLM tokens, long tool output). CLI mutates existing node. | **Only** `kukuvaia.tokens.delta` — no other keys. Emitted ≤ once per 100 ms per span to avoid flood. |
| `end` | Span closed. CLI marks node ended. | Final attributes: `kukuvaia.status`, `kukuvaia.summary`, `kukuvaia.tokens.input/output` (cumulative), and on error: `kukuvaia.error.type`/`message`. |

**Cancellation**: when user aborts a turn (e.g. `esc` in CLI), engine MUST close in-flight spans with `status="cancelled"` before closing SSE stream. No error attributes set for cancelled spans.

**Well-formed spans assumption**: parent span `end` always arrives after all children `end` (OTel API guarantee). CLI's reset logic depends on this.

### Extension Step D: SSE wire format

SSE `data:` payload JSON discriminator `type: "span_event"`:

```json
{"type":"span_event","spanId":"7a3f...","parentSpanId":"","name":"role:supervisor","phase":"start","attributes":{"kukuvaia.session.id":"s-42"},"timestamp":"2026-04-16T21:02:11Z"}
{"type":"span_event","spanId":"8b4e...","parentSpanId":"7a3f...","name":"tool:grep","phase":"start","attributes":{"input_preview":"pattern=foo"},"timestamp":"..."}
{"type":"span_event","spanId":"8b4e...","parentSpanId":"7a3f...","name":"tool:grep","phase":"end","attributes":{"kukuvaia.summary":"3 matches in 2 files","kukuvaia.status":"success"},"timestamp":"..."}
```

No separate endpoint — existing `/api/chat` SSE stream carries these alongside content blocks.

### Extension acceptance criteria

- [ ] `SpanEventBlock` added to sealed `OutputBlock` hierarchy
- [ ] `SpanEventEmittingAdvisor` registered as OTel `SpanProcessor`
- [ ] SSE endpoint emits `span_event` frames for all `kukuvaia.*`-tagged spans
- [ ] Only spans with `kukuvaia.session.id` attribute are forwarded (security: no leaks from unrelated traces)
- [ ] Span naming convention documented and enforced at emission sites (`role:*`, `tool:*`, `llm:*`, `memory:*`)
- [ ] `delta` phase carries only `kukuvaia.tokens.delta`, throttled to ≤ 1 emission / 100 ms / span
- [ ] User cancellation (`esc` / client disconnect) closes in-flight spans with `status="cancelled"`
- [ ] `status="error"` spans include `kukuvaia.error.type` and `kukuvaia.error.message` (PII-sanitized, 200-char cap)
- [ ] `SseEmitterRegistry.emit()` serialized per session — concurrent sub-agent spans produce no interleaved JSON
- [ ] Round-trip test: trigger a multi-step chat → SSE stream contains expected span tree structure
- [ ] Concurrency test: two parallel sub-agents emit spans concurrently → well-ordered SSE output
- [ ] No regression in existing `OutputBlock` serialization

### Extension effort estimate

| Task | Effort |
|------|--------|
| `SpanEventBlock` record + OutputBlock hierarchy | ~0.5h |
| `SpanEventEmittingAdvisor` + OTel registration | ~1.5h |
| Span name conventions at emission sites (`role:*`, `tool:*`, `llm:*`, `memory:*`) | ~1.5h |
| SSE integration + round-trip test | ~1h |
| **Extension total** | **~4.5h** |

**Grand total P01 (Steps 1-10 + Enhancements A/B + SpanEventBlock extension)**: **~14.5h**

---

## Priority & Prerequisites

**Priority:** Medium-High -- not blocking functionality, but essential for production readiness. With Koog-inspired enhancements, also provides per-agent-run debugging capability.

**Prerequisites:**
- None. All code changes are additive (new dependency, new constructor parameter, new metric calls). No breaking changes.
- OTel API dependencies are `compileOnly` — no-op when OTel agent is not attached.

**Sequencing:**
- Should be done FIRST among P0x plans — every subsequent change benefits from metrics/traces.
- Should precede P03 (model fallback chain) so fallback events are observable from day one.
- Should precede P12 (agent reflection / dreaming self-optimization) — dreaming needs observable metrics.
- **Unblocks P01.1** (CLI activity tracker) — `SpanEventBlock` extension must ship as part of P01 for P01.1 to begin.
- Grafana dashboards (Step 10) can be deferred and built incrementally as the system runs in staging.

**Risk:**
- Low. Micrometer integration is well-documented and non-invasive.
- The only constructor signature changes are adding `MeterRegistry`, which requires updating existing tests (inject `SimpleMeterRegistry`). This is mechanical.
- AOP for tool timing (Step 7) requires `spring-boot-starter-aop`; verify no conflicts with existing component scanning.
- OTel `compileOnly` dependencies are safe: `Span.current()` and `Context.current()` return no-op implementations when the OTel agent is not present.

---

## Langfuse Integration — LLM-Specific Observability UI (2026-04-17)

**Rationale**: P01 Steps 1-10 + Enhancements A/B give us infrastructure-grade observability (JVM, HTTP, JDBC, latency histograms, Prometheus metrics) plus distributed traces exported to Jaeger / Grafana Tempo. This is sufficient for **operators** debugging "why is this endpoint slow?" — but it is **not optimal for prompt engineers** debugging "why did the LLM produce this bad answer?".

Jaeger/Tempo UIs were designed for microservice request flows. They show spans as timeline bars, not as prompt/response pairs. Looking at a failed answer requires:
1. Find the trace ID
2. Open Jaeger
3. Click through the span tree
4. Copy-paste prompt payloads from attribute fields
5. Manually compare across sessions

[Langfuse](https://langfuse.com) (open source, MIT, self-hostable) is purpose-built for LLM ops:
- Prompt/response UI with diff view across runs
- Per-trace cost computation (tokens × model pricing)
- User feedback annotations (thumb-up/down, free text) tied to traces
- Dataset management with UI-driven curation (feeds into P05 eval pipeline)
- A/B testing of prompts with statistical significance
- Ingests **standard OTel OTLP** — zero custom instrumentation needed

Since P01 already emits OTel spans with `kukuvaia.*` attributes, Langfuse integration is **one environment variable away**: add it as a second OTLP exporter alongside Jaeger/Tempo.

### Langfuse Step A: Run Langfuse locally (self-hosted)

**`kukuvaia-engine/docker-compose.yml` — add services:**

```yaml
services:
  langfuse-db:
    image: postgres:16
    environment:
      POSTGRES_USER: langfuse
      POSTGRES_PASSWORD: langfuse
      POSTGRES_DB: langfuse
    volumes:
      - langfuse-db-data:/var/lib/postgresql/data

  langfuse:
    image: langfuse/langfuse:latest
    depends_on: [langfuse-db]
    ports:
      - "3000:3000"
    environment:
      DATABASE_URL: postgresql://langfuse:langfuse@langfuse-db:5432/langfuse
      NEXTAUTH_URL: http://localhost:3000
      NEXTAUTH_SECRET: change-me-in-prod
      SALT: change-me-in-prod
      TELEMETRY_ENABLED: "false"

volumes:
  langfuse-db-data:
```

Visit `http://localhost:3000`, create a project, copy the public+secret key pair.

### Langfuse Step B: Configure OTel exporter to dual-send

Langfuse accepts OTLP traces at `POST /api/public/otel/v1/traces` with basic auth (public key : secret key base64-encoded).

**Environment variables for startup:**

```bash
# Existing OTel exporter (Jaeger/Tempo) — unchanged
OTEL_SERVICE_NAME=kukuvaia-engine
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc

# Second exporter for Langfuse (HTTP/protobuf)
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://cloud.langfuse.com/api/public/otel/v1/traces
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_TRACES_HEADERS=Authorization=Basic <base64(pk:sk)>
```

For **dual export** (both Jaeger AND Langfuse), use the OTel Collector as a fan-out:

```yaml
# otel-collector-config.yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls: { insecure: true }
  otlphttp/langfuse:
    endpoint: https://cloud.langfuse.com/api/public/otel
    headers:
      Authorization: Basic <base64(pk:sk)>

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp/jaeger, otlphttp/langfuse]
```

Engine exports once to Collector on `:4317`; Collector fans out. Zero duplicated network I/O from the JVM.

### Langfuse Step C: Span attribute conventions for optimal Langfuse UI

Langfuse maps specific OTel attribute names to first-class UI concepts. Update emission sites to include these:

| Langfuse concept | OTel attribute | Emit in |
|------------------|----------------|---------|
| Input prompt | `gen_ai.prompt` (JSON) | `ProviderAuditLog.before()` |
| Output response | `gen_ai.completion` (JSON) | `ProviderAuditLog.after()` |
| Model | `gen_ai.request.model` | `ProviderAuditLog.before()` |
| Provider | `gen_ai.system` | `ProviderAuditLog.before()` |
| Prompt tokens | `gen_ai.usage.prompt_tokens` | `ProviderAuditLog.after()` |
| Completion tokens | `gen_ai.usage.completion_tokens` | `ProviderAuditLog.after()` |
| User ID | `user.id` | `AgentService.streamChat` |
| Session ID | `session.id` | `AgentService.streamChat` |

These follow the [OTel GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/) — same attributes also recognized by Grafana Tempo's LLM view, so no vendor lock-in.

**Change in `ProviderAuditLog.before()`** (extends Enhancement A):

```java
Span currentSpan = Span.current();
currentSpan.setAttribute("gen_ai.system", provider);
currentSpan.setAttribute("gen_ai.request.model", model);
currentSpan.setAttribute("gen_ai.prompt", serializePrompt(request.prompt().getInstructions()));
currentSpan.setAttribute("session.id", sessionId);
currentSpan.setAttribute("user.id", userId);
```

**Change in `ProviderAuditLog.after()`:**

```java
currentSpan.setAttribute("gen_ai.completion", response.chatResponse().getResult().getOutput().getContent());
currentSpan.setAttribute("gen_ai.usage.prompt_tokens", usage.getPromptTokens());
currentSpan.setAttribute("gen_ai.usage.completion_tokens", usage.getCompletionTokens());
```

**PII note**: `gen_ai.prompt` and `gen_ai.completion` carry full message bodies. For production: gate emission behind `kukuvaia.observability.langfuse.include-payloads: true` config flag (default `false` if dealing with user PII). Truncate to 4000 chars. Reuse `ErrorSanitizer` (per security/runtime standard) for payload scrubbing.

### Langfuse Step D: Dataset feedback loop with P05

Langfuse's "Datasets" feature stores curated prompt/expected-output pairs. P05 eval pipeline can read these via Langfuse Java SDK (`io.langfuse:langfuse-java`) as an alternative source to JSONL files:

```java
// kukuvaia-core/src/main/java/ai/kukuvaia/eval/dataset/LangfuseDatasetLoader.java
public List<EvalCase> load(String datasetName) {
    return langfuseClient.datasets().get(datasetName).items().stream()
        .map(item -> new EvalCase(
            item.id(),
            (String) item.metadata().get("category"),
            List.of(),
            item.input(),
            item.expectedOutput(),
            Map.of()))
        .toList();
}
```

Prompt engineers curate in Langfuse UI; CI runs evals against the same dataset. Round-trip closed.

### Langfuse acceptance criteria

- [ ] Langfuse runs locally via `docker-compose up langfuse langfuse-db`
- [ ] OTel Collector fans out to Jaeger AND Langfuse; single failure of either sink does not affect the other
- [ ] `ProviderAuditLog` sets `gen_ai.*` attributes following OTel GenAI semantic conventions
- [ ] `session.id` and `user.id` attributes populated on root chat span
- [ ] PII-sensitive payload fields (`gen_ai.prompt`/`gen_ai.completion`) behind config flag, disabled by default in prod profile
- [ ] Payload sanitization reuses `ErrorSanitizer` before emission
- [ ] Langfuse UI shows chat sessions grouped by `session.id`, with cost per trace
- [ ] `LangfuseDatasetLoader` integrates with P05 `OfflineEvalRunner` (optional, Phase 2)

### Langfuse effort estimate

| Task | Effort |
|------|--------|
| docker-compose Langfuse stack | ~0.5h |
| OTel Collector fan-out config | ~0.5h |
| `gen_ai.*` attributes in `ProviderAuditLog` | ~1h |
| PII gate + payload sanitization | ~1h |
| `LangfuseDatasetLoader` (optional, defer to P05) | ~1.5h |
| **Langfuse total** | **~4.5h** (3h without SDK integration) |

**Grand total P01 with Langfuse**: **~19h** (or ~17.5h deferring dataset integration to P05).

### Langfuse vs Jaeger/Tempo — when to use which

| Use case | Tool |
|---|---|
| "Why is endpoint X slow?" | Jaeger / Grafana Tempo |
| "Why did the LLM produce this answer?" | Langfuse |
| JVM heap, HikariCP, HTTP latency | Prometheus + Grafana |
| Cost per user, cost per session | Langfuse |
| Distributed trace across services | Jaeger / Tempo |
| Prompt A/B test statistical significance | Langfuse |

Not "either/or" — both run in parallel, consuming the same OTel stream. Ops team lives in Grafana; prompt engineers live in Langfuse.

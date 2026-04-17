# P07: Cost Tracking — Per-Request Cost Calculation, Budgets, and Reporting

**Created**: 2026-04-10
**Status**: Draft
**Module**: kukuvaia-core, kukuvaia-app (migration)
**Depends on**: ProviderAuditLog, DaemonBudgetGuard, TokenBudgetAdvisor, DB-backed model registry

---

## Problem

Kukuvaia tracks token usage (ProviderAuditLog logs promptTokens/completionTokens, DaemonBudgetGuard enforces daily token budgets, TokenBudgetAdvisor tracks per-session usage) but has no concept of monetary cost. Without cost tracking:

- **No visibility** into how much each conversation, user, or day costs in USD
- **No cost-per-model awareness** — Opus costs 15x more than Haiku, but the system treats all tokens equally
- **Budget enforcement is token-based only** — 100K tokens of Haiku costs ~$0.03 while 100K tokens of Opus costs ~$4.50
- **No cost reporting** — administrators cannot review spending patterns
- **No cost-aware routing** — the model routing advisor (from model-routing-and-embabel.md) cannot factor in cost when selecting models
- **Users have no feedback** — CLI shows no cost information per response

---

## Current State

| Component | What it tracks | Gap |
|-----------|---------------|-----|
| `ProviderAuditLog` | provider, model, promptTokens, completionTokens, totalTokens (MDC log) | No USD cost, no persistence |
| `DaemonBudgetGuard` | Daily token usage from `daemon_tasks.token_usage` | Token-based only, daemon-only |
| `TokenBudgetAdvisor` | Per-session token count (in-memory ConcurrentHashMap) | Token-based, volatile, no persistence |
| `ModelRecord` | model_id, tier, max_tokens, capabilities | No pricing info |
| `ProviderRecord` | provider type, base_url | No default pricing |

Token usage flows through the system but is never converted to monetary cost, never persisted per-request, and never exposed to users.

---

## Architecture

```
ChatClient request
  │
  ▼
┌──────────────────────────────────────────────────────┐
│              CostTrackingAdvisor                      │
│           (HIGHEST_PRECEDENCE + 3)                    │
│                                                       │
│  before():                                            │
│    └── record start time, model context               │
│                                                       │
│  after():                                             │
│    ├── Extract usage: promptTokens, completionTokens  │
│    ├── CostCalculator.calculate(model, tokens)        │
│    │     └── ModelPricing lookup (DB or config)       │
│    ├── Persist to kukuvaia.cost_log table             │
│    ├── Set MDC: cost_usd, model                       │
│    ├── Update in-memory session cost accumulator      │
│    ├── Check budget alerts (session, user, daily)     │
│    └── Attach cost to advisor context for downstream  │
└──────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────┐
│              CostCalculator                           │
│                                                       │
│  ModelPricing(modelPattern, inputPer1K, outputPer1K)  │
│    ├── "claude-opus*"     → $0.015 / $0.075          │
│    ├── "claude-sonnet*"   → $0.003 / $0.015          │
│    ├── "claude-haiku*"    → $0.00025 / $0.00125      │
│    ├── "gpt-4o"           → $0.005 / $0.015          │
│    └── (configurable via DB or application.yaml)      │
│                                                       │
│  calculate(model, inputTokens, outputTokens) → $USD  │
└──────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────┐
│              kukuvaia.cost_log (PostgreSQL)           │
│                                                       │
│  id | session_id | user_id | model | provider        │
│  input_tokens | output_tokens | cost_usd | context   │
│  created_at                                           │
└──────────────────────────────────────────────────────┘
  │
  ▼
┌──────────────────────────────────────────────────────┐
│              MetadataBlock (SSE → CLI)                │
│                                                       │
│  { "tokens": 1234, "cost_usd": 0.0042,              │
│    "model": "claude-haiku-3.5", "session_total": 0.12}│
└──────────────────────────────────────────────────────┘
```

---

## Implementation

### Step 1: Flyway migration — cost_log table

**File**: `kukuvaia-app/src/main/resources/db/migration/V7__create_cost_log.sql`

```sql
CREATE TABLE kukuvaia.cost_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(255) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    provider_name   VARCHAR(100),
    model_id        VARCHAR(255) NOT NULL,
    execution_context VARCHAR(20) DEFAULT 'interactive',  -- interactive | daemon | subagent
    input_tokens    INT NOT NULL DEFAULT 0,
    output_tokens   INT NOT NULL DEFAULT 0,
    total_tokens    INT NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(10, 6) NOT NULL DEFAULT 0,
    latency_ms      INT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cost_log_session ON kukuvaia.cost_log(session_id);
CREATE INDEX idx_cost_log_user ON kukuvaia.cost_log(user_id);
CREATE INDEX idx_cost_log_created ON kukuvaia.cost_log(created_at);
CREATE INDEX idx_cost_log_user_day ON kukuvaia.cost_log(user_id, (created_at::date));

-- Add pricing columns to models table
ALTER TABLE kukuvaia.models
    ADD COLUMN IF NOT EXISTS input_price_per_1k  NUMERIC(10, 6),
    ADD COLUMN IF NOT EXISTS output_price_per_1k NUMERIC(10, 6);

COMMENT ON TABLE kukuvaia.cost_log IS 'Per-request cost tracking for all LLM calls';
COMMENT ON COLUMN kukuvaia.cost_log.cost_usd IS 'Calculated cost in USD based on model pricing at time of request';
```

### Step 2: ModelPricing and CostCalculator

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/cost/ModelPricing.java`

```java
package ai.kukuvaia.cost;

import java.math.BigDecimal;

/**
 * Pricing for a specific model. Cost per 1,000 tokens (input/output).
 */
public record ModelPricing(
        String modelPattern,          // glob pattern: "claude-haiku*", "gpt-4o"
        BigDecimal inputPer1K,        // USD per 1K input tokens
        BigDecimal outputPer1K        // USD per 1K output tokens
) {}
```

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostCalculator.java`

```java
package ai.kukuvaia.cost;

import ai.kukuvaia.provider.registry.ModelRepository;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Calculates USD cost for a given model and token count.
 *
 * Resolution order:
 * 1. DB model pricing (models.input_price_per_1k / output_price_per_1k)
 * 2. Config-based pricing (kukuvaia.cost.pricing.* from application.yaml)
 * 3. Fallback: $0 (unknown model — logged as warning)
 */
@Component
public class CostCalculator {

    private static final BigDecimal PER_1K = new BigDecimal("1000");

    private final ModelRepository modelRepository;
    private final CostConfig costConfig;

    public CostCalculator(ModelRepository modelRepository, CostConfig costConfig) {
        this.modelRepository = modelRepository;
        this.costConfig = costConfig;
    }

    /**
     * Calculate cost in USD.
     */
    public BigDecimal calculate(String modelId, int inputTokens, int outputTokens) {
        ModelPricing pricing = resolvePricing(modelId);
        if (pricing == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal inputCost = pricing.inputPer1K()
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(PER_1K, 6, RoundingMode.HALF_UP);

        BigDecimal outputCost = pricing.outputPer1K()
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(PER_1K, 6, RoundingMode.HALF_UP);

        return inputCost.add(outputCost);
    }

    private ModelPricing resolvePricing(String modelId) {
        // 1. Try DB pricing
        // 2. Try config glob patterns
        // 3. Return null (logged as warning by caller)
    }
}
```

### Step 3: CostConfig (configuration properties)

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostConfig.java`

```java
@ConfigurationProperties(prefix = "kukuvaia.cost")
public record CostConfig(
        boolean enabled,
        List<ModelPricing> pricing,           // fallback pricing from yaml
        BigDecimal dailyBudgetUsd,            // per-user daily budget in USD
        BigDecimal sessionBudgetUsd,          // per-session budget in USD
        BigDecimal alertThreshold             // 0.8 = alert at 80%
) {
    public CostConfig {
        if (pricing == null) pricing = List.of();
        if (dailyBudgetUsd == null) dailyBudgetUsd = new BigDecimal("10.00");
        if (sessionBudgetUsd == null) sessionBudgetUsd = new BigDecimal("2.00");
        if (alertThreshold == null) alertThreshold = new BigDecimal("0.80");
    }
}
```

### Step 4: CostRepository

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostRepository.java`

```java
@Component
public class CostRepository {

    private final JdbcTemplate jdbc;

    /** Insert a cost log entry. */
    public void log(CostLogEntry entry) {
        jdbc.update("""
            INSERT INTO kukuvaia.cost_log
                (session_id, user_id, provider_name, model_id, execution_context,
                 input_tokens, output_tokens, total_tokens, cost_usd, latency_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            entry.sessionId(), entry.userId(), entry.providerName(), entry.modelId(),
            entry.executionContext(), entry.inputTokens(), entry.outputTokens(),
            entry.totalTokens(), entry.costUsd(), entry.latencyMs());
    }

    /** Daily cost for a user. */
    public BigDecimal dailyCostForUser(String userId) { ... }

    /** Session cost total. */
    public BigDecimal sessionCost(String sessionId) { ... }

    /** Daily report: per-model breakdown. */
    public List<CostSummary> dailyReport(String userId, LocalDate date) { ... }

    /** Weekly report: day-by-day totals. */
    public List<CostSummary> weeklyReport(String userId, LocalDate weekStart) { ... }
}
```

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostLogEntry.java`

```java
public record CostLogEntry(
        String sessionId,
        String userId,
        String providerName,
        String modelId,
        String executionContext,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        BigDecimal costUsd,
        Integer latencyMs
) {}
```

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostSummary.java`

```java
public record CostSummary(
        String label,            // model name or date
        int totalRequests,
        int totalInputTokens,
        int totalOutputTokens,
        BigDecimal totalCostUsd
) {}
```

### Step 5: CostTrackingAdvisor

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostTrackingAdvisor.java`

```java
@Component
public class CostTrackingAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingAdvisor.class);
    private static final String CTX_START_TIME = "kukuvaia.cost.startTime";

    private final CostCalculator calculator;
    private final CostRepository repository;
    private final CostConfig config;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
        // After ProviderAuditLog (+0), ToolResultSanitizing (+1), ContentModeration (+2)
        // Before SmartMemoryAdvisor (+5)
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!config.enabled()) return request;

        return request.mutate()
                .context(Map.of(CTX_START_TIME, System.currentTimeMillis()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!config.enabled()) return response;

        var chatResponse = response.chatResponse();
        if (chatResponse == null) return response;

        var usage = chatResponse.getMetadata().getUsage();
        if (usage == null) return response;

        int inputTokens = (int) usage.getPromptTokens();
        int outputTokens = (int) usage.getCompletionTokens();
        String model = extractModel(chatResponse);
        String sessionId = extractSessionId(response);
        String userId = extractUserId(response);
        String provider = extractProvider(response);
        String context = extractContext(response);
        long startTime = extractStartTime(response);

        BigDecimal cost = calculator.calculate(model, inputTokens, outputTokens);

        // Persist
        repository.log(new CostLogEntry(
                sessionId, userId, provider, model, context,
                inputTokens, outputTokens, inputTokens + outputTokens,
                cost, startTime > 0 ? (int)(System.currentTimeMillis() - startTime) : null
        ));

        // MDC for structured logging
        MDC.put("cost_usd", cost.toPlainString());
        MDC.put("model", model);
        log.info("Cost: model={}, tokens={}+{}, cost_usd={}, session={}, user={}",
                model, inputTokens, outputTokens, cost, sessionId, userId);
        MDC.remove("cost_usd");
        MDC.remove("model");

        // Check budget alerts
        checkBudgetAlerts(sessionId, userId);

        // Attach cost to context for downstream (MetadataBlock generation)
        return response; // cost info available via CostRepository for MetadataBlock
    }

    private void checkBudgetAlerts(String sessionId, String userId) {
        BigDecimal dailyCost = repository.dailyCostForUser(userId);
        BigDecimal sessionCost = repository.sessionCost(sessionId);

        if (dailyCost.compareTo(config.dailyBudgetUsd()) >= 0) {
            log.warn("BUDGET EXCEEDED: user={} daily cost ${} >= limit ${}",
                    userId, dailyCost, config.dailyBudgetUsd());
        } else if (dailyCost.compareTo(
                config.dailyBudgetUsd().multiply(config.alertThreshold())) >= 0) {
            log.warn("Budget alert: user={} daily cost ${} at {}% of limit",
                    userId, dailyCost,
                    dailyCost.divide(config.dailyBudgetUsd(), 2, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)));
        }
    }
}
```

### Step 6: Cost reporting endpoints

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/api/CostController.java`

```java
@RestController
@RequestMapping("/api/cost")
public class CostController {

    private final CostRepository costRepository;

    @GetMapping("/session/{sessionId}")
    public Map<String, Object> sessionCost(@PathVariable String sessionId) {
        BigDecimal total = costRepository.sessionCost(sessionId);
        return Map.of("sessionId", sessionId, "totalCostUsd", total);
    }

    @GetMapping("/daily")
    public List<CostSummary> dailyReport(
            @RequestParam String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return costRepository.dailyReport(userId, date);
    }

    @GetMapping("/weekly")
    public List<CostSummary> weeklyReport(
            @RequestParam String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return costRepository.weeklyReport(userId, weekStart);
    }

    @GetMapping("/budget")
    public Map<String, Object> budgetStatus(@RequestParam String userId) {
        BigDecimal dailyCost = costRepository.dailyCostForUser(userId);
        // Return remaining budget, percentage used, etc.
    }
}
```

### Step 7: MetadataBlock cost info

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java`

After a successful chat response, append a `MetadataBlock` with cost info:

```java
BigDecimal requestCost = costRepository.lastRequestCost(sessionId);
BigDecimal sessionTotal = costRepository.sessionCost(sessionId);

var metadata = Map.of(
        "tokens", totalTokens,
        "cost_usd", requestCost,
        "session_total_usd", sessionTotal,
        "model", modelUsed
);

return Flux.just(new TextBlock(response, null), new MetadataBlock(metadata));
```

CLI renders this as a subtle footer line:
```
[haiku] 342 tokens | $0.0001 | session: $0.0042
```

### Step 8: Cost-aware model routing (future integration point)

The `CostCalculator` exposes pricing info that the `ModelRoutingAdvisor` (from model-routing-and-embabel.md Phase 5) can use to prefer cheaper models:

```java
// In ModelRoutingAdvisor.before():
BigDecimal dailyCost = costRepository.dailyCostForUser(userId);
if (dailyCost.compareTo(config.dailyBudgetUsd().multiply(COST_AWARENESS_THRESHOLD)) >= 0) {
    // Prefer cheaper model when approaching budget limit
    targetRole = "worker"; // Haiku instead of Sonnet
}
```

This is a future integration — not implemented in this plan.

---

## Configuration

### application.yaml additions

```yaml
kukuvaia:
  cost:
    enabled: ${KUKUVAIA_COST_TRACKING:true}
    daily-budget-usd: 10.00
    session-budget-usd: 2.00
    alert-threshold: 0.80
    pricing:
      - model-pattern: "claude-opus*"
        input-per-1k: 0.015
        output-per-1k: 0.075
      - model-pattern: "claude-sonnet*"
        input-per-1k: 0.003
        output-per-1k: 0.015
      - model-pattern: "claude-haiku*"
        input-per-1k: 0.00025
        output-per-1k: 0.00125
      - model-pattern: "gpt-4o*"
        input-per-1k: 0.005
        output-per-1k: 0.015
      - model-pattern: "gpt-4o-mini*"
        input-per-1k: 0.00015
        output-per-1k: 0.0006
```

Pricing is overridden per-model in the `models` table (DB takes precedence over config).

---

## Dependencies

| Dependency | Purpose | New? |
|-----------|---------|------|
| Spring AI BaseAdvisor | Advisor interface | Existing |
| JdbcTemplate | cost_log persistence | Existing |
| ProviderAuditLog context | Extracts provider/model from advisor context | Existing |
| ModelRepository | DB-based pricing lookup | Existing (from model-routing plan) |

No new external dependencies.

---

## File Inventory

### New files (8)

| File | Description |
|------|-------------|
| `kukuvaia-app/src/main/resources/db/migration/V7__create_cost_log.sql` | cost_log table + models pricing columns |
| `kukuvaia-core/src/main/java/ai/kukuvaia/cost/ModelPricing.java` | Pricing record per model pattern |
| `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostCalculator.java` | USD cost calculation |
| `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostConfig.java` | @ConfigurationProperties |
| `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostRepository.java` | JDBC persistence + reporting queries |
| `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostLogEntry.java` | Cost log entry record |
| `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostSummary.java` | Aggregated cost summary record |
| `kukuvaia-core/src/main/java/ai/kukuvaia/cost/CostTrackingAdvisor.java` | BaseAdvisor for cost tracking |
| `kukuvaia-core/src/main/java/ai/kukuvaia/api/CostController.java` | REST endpoints for cost reports |

### Modified files (3)

| File | Change |
|------|--------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java` | Add CostTrackingAdvisor to chain |
| `kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java` | Append MetadataBlock with cost info |
| `kukuvaia-app/src/main/resources/application.yaml` | Add kukuvaia.cost.* section |

### Test files (4)

| File | What it tests |
|------|--------------|
| `kukuvaia-core/src/test/java/ai/kukuvaia/cost/CostCalculatorTest.java` | Pricing resolution, calculation accuracy, unknown model fallback |
| `kukuvaia-core/src/test/java/ai/kukuvaia/cost/CostTrackingAdvisorTest.java` | Before/after flow, disabled config, budget alerts |
| `kukuvaia-core/src/test/java/ai/kukuvaia/cost/CostRepositoryTest.java` | Insert, daily/session queries, weekly report (Testcontainers) |
| `kukuvaia-core/src/test/java/ai/kukuvaia/cost/CostConfigTest.java` | Config parsing, defaults |

---

## Verification

### Unit tests

```bash
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.cost.*"
```

Expected:
- `CostCalculatorTest`: Haiku 1000 input + 500 output → $0.000875; Opus same tokens → $0.0525; unknown model → $0 + warning
- `CostTrackingAdvisorTest`: disabled → pass-through; after() persists cost; budget alert at threshold
- `CostConfigTest`: defaults applied, pricing list parsed

### Integration tests

```bash
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.cost.CostRepositoryTest"
```

With Testcontainers PostgreSQL:
1. Insert cost_log entry → read back correctly
2. `dailyCostForUser()` sums today's costs only
3. `sessionCost()` sums costs for specific session
4. `dailyReport()` returns per-model breakdown

### Manual verification

```bash
# Start with cost tracking enabled
KUKUVAIA_COST_TRACKING=true ./gradlew :kukuvaia-app:bootRun

# Send a chat message
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"cost-test","message":"hello"}'
# Check SSE stream includes MetadataBlock with cost_usd

# Check cost log
curl http://localhost:8080/api/cost/session/cost-test
# Expected: {"sessionId":"cost-test","totalCostUsd":0.000XXX}

# Check daily report
curl "http://localhost:8080/api/cost/daily?userId=bartek&date=2026-04-10"

# Verify logs
grep "Cost:" logs/kukuvaia.log | head -5
# Expected: Cost: model=claude-haiku-3.5, tokens=100+50, cost_usd=0.000125, ...
```

---

## Effort Estimate

| Phase | Scope | Effort |
|-------|-------|--------|
| Phase 1 | Flyway migration (cost_log table + models pricing columns) | 0.5 day |
| Phase 2 | ModelPricing, CostCalculator, CostConfig | 1 day |
| Phase 3 | CostRepository (JDBC queries) | 1 day |
| Phase 4 | CostTrackingAdvisor (before/after, budget alerts) | 1 day |
| Phase 5 | CostController (REST endpoints) | 0.5 day |
| Phase 6 | MetadataBlock cost info in AgentService | 0.5 day |
| Phase 7 | Tests (unit + integration with Testcontainers) | 1.5 days |
| **Total** | | **6 days** |

---

## Priority & Prerequisites

**Priority**: Medium — valuable for visibility and budget control, but not blocking core functionality.

**Prerequisites**:
- DB-backed model registry (model-routing-and-embabel.md Phase 1-2) — for DB pricing lookup
- ProviderAuditLog operational (already done) — provides token counts in advisor context
- Flyway migrations V1-V6 applied (already done)

**Blocked by**: Nothing critical — config-based pricing works without DB model registry as a fallback.

**Blocks**:
- Cost-aware model routing (integration point in ModelRoutingAdvisor)
- Enterprise billing features
- Usage dashboards in future kukuvaia-web

package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.provider.model.ExecutionContext;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentResult;
import ai.kukuvaia.agent.subagent.SubAgentSpec;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Orchestrates daemon task execution via sub-agents.
 * Integrates all security guards: budget, schedule overlap, provider routing.
 *
 * Covers: Finding #10 (task flood), #15 (audit trail), #17 (cron overlap).
 */
@Service
public class DaemonAgentService {

    private static final Logger log = LoggerFactory.getLogger(DaemonAgentService.class);

    private final SubAgentFactory subAgentFactory;
    private final DaemonBudgetGuard budgetGuard;
    private final DaemonScheduleGuard scheduleGuard;
    private final NotificationSanitizer notificationSanitizer;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;
    private final DaemonProperties properties;

    public DaemonAgentService(SubAgentFactory subAgentFactory,
                               DaemonBudgetGuard budgetGuard,
                               DaemonScheduleGuard scheduleGuard,
                               NotificationSanitizer notificationSanitizer,
                               JdbcTemplate jdbc,
                               MeterRegistry meterRegistry,
                               DaemonProperties properties) {
        this.subAgentFactory = subAgentFactory;
        this.budgetGuard = budgetGuard;
        this.scheduleGuard = scheduleGuard;
        this.notificationSanitizer = notificationSanitizer;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    /**
     * Execute a daemon task with all security guards.
     *
     * @param taskName         unique task name (used for overlap detection)
     * @param specialistType   specialist to delegate to
     * @param prompt           sanitized prompt for sub-agent
     * @param triggerSource    "cron", "webhook:ci", "pg-notify:channel", "manual"
     * @param providerOverride explicit provider or null for daemon default
     * @return task result with full audit metadata
     */
    public DaemonTaskResult execute(String taskName, String specialistType,
                                     String prompt, String triggerSource,
                                     String providerOverride) {
        // Guard 1: budget check
        if (!budgetGuard.canExecute()) {
            log.warn("Daemon task '{}' skipped — daily token budget exhausted", taskName);
            return failedResult(taskName, specialistType, triggerSource,
                    "Daily token budget exhausted");
        }

        // Guard 2: overlap check (skip if same task already running)
        if (!scheduleGuard.tryAcquire(taskName)) {
            log.info("Daemon task '{}' skipped — previous execution still running", taskName);
            return failedResult(taskName, specialistType, triggerSource,
                    "Previous execution still running");
        }

        Instant startedAt = Instant.now();
        long taskId = insertTask(taskName, specialistType, prompt, triggerSource);

        try {
            // Execute via sub-agent (inherits all sub-agent security: tool filtering, depth guard,
            // tool-round limit, timeout). The daemon allowlist narrows tools for every daemon run.
            SubAgentResult run = subAgentFactory.executeForResult(prompt, specialistType,
                    ExecutionContext.DAEMON, providerOverride, 0, properties.toolScopeOrNull());
            String result = run.content();
            int tokens = billableTokens(run, specialistType);

            Instant completedAt = Instant.now();
            long durationMs = Duration.between(startedAt, completedAt).toMillis();

            // Update task record, then account the tokens against the daily budget
            completeTask(taskId, result, durationMs);
            budgetGuard.recordUsage(taskId, tokens);

            var taskResult = new DaemonTaskResult(
                    taskId, taskName, specialistType,
                    providerOverride != null ? providerOverride : "daemon-default",
                    DaemonTaskStatus.COMPLETED, result, null,
                    tokens, durationMs, triggerSource, startedAt, completedAt);

            log.info("Daemon task '{}' completed in {}ms (task #{})",
                    taskName, durationMs, taskId);

            recordMetrics(taskName, specialistType, "completed", durationMs);
            return taskResult;

        } catch (Exception e) {
            Instant completedAt = Instant.now();
            long durationMs = Duration.between(startedAt, completedAt).toMillis();
            failTask(taskId, e.getMessage(), durationMs);
            // A timed-out call may already have consumed tokens the provider never reported:
            // charge the specialist's max_tokens (fail closed). Other failures happen before or
            // without a billed completion, so nothing is charged.
            if (e instanceof SubAgentFactory.SubAgentTimeoutException) {
                budgetGuard.recordUsage(taskId, maxTokensOf(specialistType));
            }

            log.error("Daemon task '{}' failed after {}ms: {}",
                    taskName, durationMs, e.getMessage());

            recordMetrics(taskName, specialistType, "failed", durationMs);
            return new DaemonTaskResult(
                    taskId, taskName, specialistType,
                    providerOverride != null ? providerOverride : "daemon-default",
                    DaemonTaskStatus.FAILED, null, e.getMessage(),
                    0, durationMs, triggerSource, startedAt, completedAt);

        } finally {
            scheduleGuard.release(taskName);
        }
    }

    /**
     * Tokens to charge against the daily budget. If the provider reported no usage, charge the
     * specialist's {@code max_tokens} instead of zero — an unknown cost must not look free.
     */
    private int billableTokens(SubAgentResult run, String specialistType) {
        if (run.usageReported()) {
            return run.totalTokens();
        }
        int estimate = maxTokensOf(specialistType);
        log.warn("Specialist '{}' reported no token usage — charging max_tokens={} to the daemon budget",
                specialistType, estimate);
        return estimate;
    }

    private int maxTokensOf(String specialistType) {
        return subAgentFactory.spec(specialistType).map(SubAgentSpec::maxTokens).orElse(0);
    }

    private void recordMetrics(String taskName, String specialist, String status, long durationMs) {
        Timer.builder("kukuvaia.daemon.task.duration")
                .tag("task", taskName)
                .tag("specialist", specialist)
                .tag("status", status)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
        Counter.builder("kukuvaia.daemon.task.total")
                .tag("task", taskName)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    private long insertTask(String name, String specialist, String prompt, String trigger) {
        return jdbc.queryForObject(
                "INSERT INTO kukuvaia_agent.daemon_tasks (name, specialist_type, prompt, trigger_source, " +
                "status, started_at, created_at) VALUES (?, ?, ?, ?, 'RUNNING', now(), now()) " +
                "RETURNING id",
                Long.class, name, specialist, prompt, trigger);
    }

    private void completeTask(long id, String result, long durationMs) {
        jdbc.update("UPDATE kukuvaia_agent.daemon_tasks SET status = 'COMPLETED', result = ?, " +
                "duration_ms = ?, completed_at = now() WHERE id = ?",
                result, durationMs, id);
    }

    private void failTask(long id, String error, long durationMs) {
        jdbc.update("UPDATE kukuvaia_agent.daemon_tasks SET status = 'FAILED', result = ?, " +
                "duration_ms = ?, completed_at = now() WHERE id = ?",
                error, durationMs, id);
    }

    private DaemonTaskResult failedResult(String name, String specialist,
                                           String trigger, String reason) {
        return new DaemonTaskResult(
                -1, name, specialist, "none",
                DaemonTaskStatus.FAILED, null, reason,
                0, 0, trigger, Instant.now(), Instant.now());
    }
}

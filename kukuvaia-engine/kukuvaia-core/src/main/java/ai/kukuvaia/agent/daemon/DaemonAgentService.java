package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.provider.model.ExecutionContext;

import ai.kukuvaia.agent.subagent.SubAgentFactory;
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
import ai.kukuvaia.provider.service.ProviderAuditLog;

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

    public DaemonAgentService(SubAgentFactory subAgentFactory,
                               DaemonBudgetGuard budgetGuard,
                               DaemonScheduleGuard scheduleGuard,
                               NotificationSanitizer notificationSanitizer,
                               JdbcTemplate jdbc,
                               MeterRegistry meterRegistry) {
        this.subAgentFactory = subAgentFactory;
        this.budgetGuard = budgetGuard;
        this.scheduleGuard = scheduleGuard;
        this.notificationSanitizer = notificationSanitizer;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
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
            // Execute via sub-agent (inherits all sub-agent security: tool filtering, depth guard, etc.)
            String result = subAgentFactory.execute(prompt, specialistType,
                    ExecutionContext.DAEMON, providerOverride);

            Instant completedAt = Instant.now();
            long durationMs = Duration.between(startedAt, completedAt).toMillis();

            // Update task record
            completeTask(taskId, result, durationMs);

            var taskResult = new DaemonTaskResult(
                    taskId, taskName, specialistType,
                    providerOverride != null ? providerOverride : "daemon-default",
                    DaemonTaskStatus.COMPLETED, result, null,
                    0, // token usage updated by ProviderAuditLog
                    durationMs, triggerSource, startedAt, completedAt);

            log.info("Daemon task '{}' completed in {}ms (task #{})",
                    taskName, durationMs, taskId);

            recordMetrics(taskName, specialistType, "completed", durationMs);
            return taskResult;

        } catch (Exception e) {
            Instant completedAt = Instant.now();
            long durationMs = Duration.between(startedAt, completedAt).toMillis();
            failTask(taskId, e.getMessage(), durationMs);

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
                "INSERT INTO daemon_tasks (name, specialist_type, prompt, trigger_source, " +
                "status, started_at, created_at) VALUES (?, ?, ?, ?, 'RUNNING', now(), now()) " +
                "RETURNING id",
                Long.class, name, specialist, prompt, trigger);
    }

    private void completeTask(long id, String result, long durationMs) {
        jdbc.update("UPDATE daemon_tasks SET status = 'COMPLETED', result = ?, " +
                "duration_ms = ?, completed_at = now() WHERE id = ?",
                result, durationMs, id);
    }

    private void failTask(long id, String error, long durationMs) {
        jdbc.update("UPDATE daemon_tasks SET status = 'FAILED', result = ?, " +
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

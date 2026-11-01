package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Persistence for {@code kukuvaia_agent.daemon_tasks} — the audit log of every daemon run
 * (security-review #15). Token usage is written by {@link DaemonBudgetGuard#recordUsage} so the
 * budget has a single writer for {@code token_usage}. All SQL is parameterized.
 */
@Component
public class DaemonTaskStore {

    private static final String TABLE = "kukuvaia_agent.daemon_tasks";

    private final JdbcTemplate jdbc;

    public DaemonTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert an admitted run in QUEUED state. */
    public long insertQueued(DaemonJobDefinition job, JobRequest request) {
        return jdbc.queryForObject(
                "INSERT INTO " + TABLE + " (name, kind, target, specialist_type, prompt, trigger_source, "
                        + "status, parent_task_id, queued_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, now(), now()) RETURNING id",
                Long.class, job.name(), job.kind().dbValue(), job.target(), specialistOf(job),
                request.prompt(), request.triggerSource(), request.parentTaskId());
    }

    /** Insert a run that was refused at admission (kill switch, budget, overlap, queue full). */
    public long insertSkipped(DaemonJobDefinition job, JobRequest request, String reason) {
        return jdbc.queryForObject(
                "INSERT INTO " + TABLE + " (name, kind, target, specialist_type, prompt, trigger_source, "
                        + "status, error, parent_task_id, queued_at, completed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'SKIPPED', ?, ?, now(), now(), now()) RETURNING id",
                Long.class, job.name(), job.kind().dbValue(), job.target(), specialistOf(job),
                request.prompt(), request.triggerSource(), reason, request.parentTaskId());
    }

    public void markRunning(long id) {
        jdbc.update("UPDATE " + TABLE + " SET status = 'RUNNING', started_at = now() WHERE id = ?", id);
    }

    public void markCompleted(long id, ExecutionOutcome outcome, long durationMs) {
        jdbc.update("UPDATE " + TABLE + " SET status = 'COMPLETED', result = ?, output = ?::jsonb, provider = ?, "
                        + "model = ?, prompt_tokens = ?, completion_tokens = ?, duration_ms = ?, completed_at = now() "
                        + "WHERE id = ?",
                outcome.text(), outcome.output() != null ? outcome.output().toString() : null,
                outcome.provider(), outcome.model(), outcome.promptTokens(), outcome.completionTokens(),
                durationMs, id);
    }

    /** Terminal non-success state (FAILED, TIMEOUT, CANCELLED). {@code error} must already be sanitized. */
    public void markFinished(long id, DaemonTaskStatus status, String error, long durationMs) {
        if (status == DaemonTaskStatus.COMPLETED || !status.isTerminal()) {
            throw new IllegalArgumentException("markFinished is for failure states, got " + status);
        }
        jdbc.update("UPDATE " + TABLE + " SET status = ?, error = ?, duration_ms = ?, completed_at = now() "
                + "WHERE id = ?", status.name(), error, durationMs, id);
    }

    private static String specialistOf(DaemonJobDefinition job) {
        return job.kind() == JobKind.SPECIALIST ? job.target() : null;
    }
}

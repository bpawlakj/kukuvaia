package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** One audit row as exposed by the daemon API. */
    public record TaskRow(long id, String name, String kind, String target, String triggerSource, String status,
                          String provider, String model, int promptTokens, int completionTokens, int tokenUsage,
                          Long durationMs, Instant queuedAt, Instant startedAt, Instant completedAt, Instant createdAt,
                          String result, String error, String output, Long parentTaskId) {}

    private static final String COLUMNS = "id, name, kind, target, trigger_source, status, provider, model, "
            + "prompt_tokens, completion_tokens, token_usage, duration_ms, queued_at, started_at, completed_at, "
            + "created_at, result, error, output::text AS output, parent_task_id";

    private static final RowMapper<TaskRow> ROW = DaemonTaskStore::mapRow;

    /** Most recent runs, optionally filtered. All filters are bound parameters. */
    public List<TaskRow> find(String job, String status, Instant since, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (job != null) {
            sql.append(" AND name = ?");
            args.add(job);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (since != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(since));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }

    public Optional<TaskRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?", ROW, id).stream().findFirst();
    }

    /**
     * Mark QUEUED/RUNNING rows created before {@code processStart} (a previous process) as CANCELLED.
     * Interrupted RUNNING LLM rows are charged at least {@code llmCharge} tokens.
     */
    public int cancelInterrupted(Instant processStart, int llmCharge) {
        return jdbc.update("UPDATE " + TABLE + " SET "
                        + "token_usage = CASE WHEN status = 'RUNNING' AND kind IN ('specialist', 'embabel') "
                        + "THEN GREATEST(token_usage, ?) ELSE token_usage END, "
                        + "error = 'interrupted by restart (was ' || status || ')', "
                        + "status = 'CANCELLED', completed_at = now() "
                        + "WHERE status IN ('QUEUED', 'RUNNING') AND created_at < ?",
                llmCharge, Timestamp.from(processStart));
    }

    /** Delete finished runs older than {@code retentionDays} (security-review #21). */
    public int pruneOlderThan(int retentionDays) {
        return jdbc.update("DELETE FROM " + TABLE + " WHERE created_at < now() - make_interval(days => ?) "
                + "AND status IN ('COMPLETED', 'FAILED', 'TIMEOUT', 'SKIPPED', 'CANCELLED')", retentionDays);
    }

    private static TaskRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRow(rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getString("target"),
                rs.getString("trigger_source"), rs.getString("status"), rs.getString("provider"), rs.getString("model"),
                rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"), rs.getInt("token_usage"),
                (Long) rs.getObject("duration_ms", Long.class), instant(rs, "queued_at"), instant(rs, "started_at"),
                instant(rs, "completed_at"), instant(rs, "created_at"), rs.getString("result"), rs.getString("error"),
                rs.getString("output"), (Long) rs.getObject("parent_task_id", Long.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static String specialistOf(DaemonJobDefinition job) {
        return job.kind() == JobKind.SPECIALIST ? job.target() : null;
    }
}

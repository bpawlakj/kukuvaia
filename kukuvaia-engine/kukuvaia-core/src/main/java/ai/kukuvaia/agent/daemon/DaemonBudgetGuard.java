package ai.kukuvaia.agent.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guards against daemon token cost explosion.
 * Covers: sub-agent cost explosion, webhook flood cost, runaway daemon tasks.
 *
 * Tracks daily token usage across all daemon tasks (parent + sub-agents).
 * Hard stop at budget limit, alert at threshold (80%).
 */
@Component
public class DaemonBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(DaemonBudgetGuard.class);

    @Value("${kukuvaia.daemon.daily-token-budget:100000}")
    private int dailyTokenBudget;

    @Value("${kukuvaia.daemon.budget-alert-threshold:0.8}")
    private double alertThreshold;

    @Value("${kukuvaia.daemon.enabled:true}")
    private boolean daemonEnabled;

    private final JdbcTemplate jdbc;

    /**
     * Tokens reserved by runs that are admitted but not yet settled. Reservations make the daily
     * cap a hard stop under concurrency: without them N concurrent runs could all pass the check
     * before any usage is recorded. Single-JVM (in-memory), like {@link DaemonScheduleGuard}.
     */
    private long reservedTokens;
    private record PendingUsage(int actual, int held) {}
    private final Map<Long, PendingUsage> pendingUsage = new LinkedHashMap<>();

    public DaemonBudgetGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserve {@code tokens} of today's budget for a run that is about to start.
     *
     * @return {@code false} if recorded usage + outstanding reservations + {@code tokens} would
     *         exceed the daily budget (the run must not start)
     */
    public synchronized boolean tryReserve(int tokens) {
        retryPendingUsage();
        int todayUsage = getTodayTokenUsage();
        long committed = todayUsage + reservedTokens();
        if (committed + tokens > dailyTokenBudget) {
            log.warn("Daemon daily token budget would be exceeded: {} used + {} reserved + {} requested > {}",
                    todayUsage, reservedTokens, tokens, dailyTokenBudget);
            return false;
        }
        reservedTokens += tokens;
        double ratio = (double) (committed + tokens) / dailyTokenBudget;
        if (ratio >= alertThreshold) {
            log.warn("Daemon token usage at {}% (incl. reservations): {}/{} tokens today",
                    Math.round(ratio * 100), committed + tokens, dailyTokenBudget);
        }
        return true;
    }

    /** Return an unused reservation (run skipped or settled). */
    public synchronized void releaseReservation(int tokens) {
        reservedTokens = Math.max(0, reservedTokens - tokens);
    }

    /** Persist conservative usage before work starts, atomically replacing its in-memory reservation. */
    public synchronized void precharge(long taskId, int reserved, int cumulativeTokens) {
        recordUsage(taskId, cumulativeTokens);
        releaseReservation(reserved);
    }

    /** Record actual usage; a failed write becomes retryable debt instead of free budget. */
    public synchronized void settle(long taskId, int reserved, int actualTokens) {
        try {
            recordUsage(taskId, actualTokens);
            releaseReservation(reserved);
        } catch (RuntimeException e) {
            int previous = pendingUsage.containsKey(taskId) ? pendingUsage.get(taskId).held() : 0;
            pendingUsage.put(taskId, new PendingUsage(actualTokens, Math.max(previous, Math.max(reserved, actualTokens))));
            releaseReservation(reserved);
            throw e;
        }
    }

    /** Outstanding reservations (monitoring / API). */
    public synchronized long reservedTokens() {
        return reservedTokens + pendingUsage.values().stream().mapToLong(PendingUsage::held).sum();
    }

    /** Configured daily budget (monitoring / API). */
    public int dailyTokenBudget() {
        return dailyTokenBudget;
    }

    /**
     * Check if daemon can execute another task.
     *
     * @return true if budget allows execution
     */
    public boolean canExecute() {
        if (!daemonEnabled) {
            log.info("Daemon disabled via configuration");
            return false;
        }

        int todayUsage = getTodayTokenUsage();

        if (todayUsage >= dailyTokenBudget) {
            log.warn("Daemon daily token budget exhausted: {}/{} tokens used today",
                    todayUsage, dailyTokenBudget);
            return false;
        }

        double usageRatio = (double) todayUsage / dailyTokenBudget;
        if (usageRatio >= alertThreshold) {
            log.warn("Daemon token usage at {}%: {}/{} tokens today",
                    Math.round(usageRatio * 100), todayUsage, dailyTokenBudget);
        }

        return true;
    }

    /**
     * Record token usage for a completed daemon task.
     */
    public synchronized void recordUsage(long taskId, int tokenCount) {
        writeUsage(taskId, tokenCount);
        pendingUsage.remove(taskId);
        try {
            int todayTotal = getTodayTokenUsage();
            log.info("Daemon token usage: {} tokens (task {}), daily total: {}/{}",
                    tokenCount, taskId, todayTotal, dailyTokenBudget);
        } catch (RuntimeException e) {
            // The UPDATE is already durable. Monitoring must not turn a successful charge into debt.
            log.warn("Usage for task {} persisted, but daily usage could not be logged", taskId, e);
        }
    }

    private void writeUsage(long taskId, int tokenCount) {
        int updated = jdbc.update(
                "UPDATE kukuvaia_agent.daemon_tasks SET token_usage = ? WHERE id = ?",
                tokenCount, taskId);
        if (updated != 1) throw new IllegalStateException("Daemon task not found while recording usage: " + taskId);
    }

    private void retryPendingUsage() {
        var pending = pendingUsage.entrySet().iterator();
        while (pending.hasNext()) {
            var entry = pending.next();
            try {
                writeUsage(entry.getKey(), entry.getValue().actual());
                pending.remove();
            } catch (RuntimeException e) {
                log.warn("Usage settlement for task {} remains pending", entry.getKey(), e);
            }
        }
    }

    /** Tokens recorded today across all daemon tasks. */
    public int todayTokenUsage() {
        return getTodayTokenUsage();
    }

    /**
     * Get remaining budget for today.
     */
    public int getRemainingBudget() {
        return Math.max(0, dailyTokenBudget - getTodayTokenUsage());
    }

    private int getTodayTokenUsage() {
        Integer usage = jdbc.queryForObject(
                "SELECT COALESCE(SUM(token_usage), 0) FROM kukuvaia_agent.daemon_tasks " +
                "WHERE COALESCE(started_at, created_at) >= ?::date AND token_usage IS NOT NULL",
                Integer.class,
                LocalDate.now());
        return usage != null ? usage : 0;
    }
}

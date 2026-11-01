package ai.kukuvaia.agent.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

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
        int todayUsage = getTodayTokenUsage();
        long committed = todayUsage + reservedTokens;
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

    /** Record the actual usage of a run and drop its reservation. */
    public void settle(long taskId, int reserved, int actualTokens) {
        try {
            recordUsage(taskId, actualTokens);
        } finally {
            releaseReservation(reserved);
        }
    }

    /** Outstanding reservations (monitoring / API). */
    public synchronized long reservedTokens() {
        return reservedTokens;
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
    public void recordUsage(long taskId, int tokenCount) {
        jdbc.update(
                "UPDATE kukuvaia_agent.daemon_tasks SET token_usage = ? WHERE id = ?",
                tokenCount, taskId);

        int todayTotal = getTodayTokenUsage();
        log.info("Daemon token usage: +{} tokens (task {}), daily total: {}/{}",
                tokenCount, taskId, todayTotal, dailyTokenBudget);
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
                "WHERE created_at >= ?::date AND token_usage IS NOT NULL",
                Integer.class,
                LocalDate.now());
        return usage != null ? usage : 0;
    }
}

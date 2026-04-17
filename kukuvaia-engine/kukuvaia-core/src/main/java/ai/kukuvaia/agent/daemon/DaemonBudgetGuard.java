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

    public DaemonBudgetGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                "UPDATE daemon_tasks SET token_usage = ? WHERE id = ?",
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
                "SELECT COALESCE(SUM(token_usage), 0) FROM daemon_tasks " +
                "WHERE created_at >= ?::date AND token_usage IS NOT NULL",
                Integer.class,
                LocalDate.now());
        return usage != null ? usage : 0;
    }
}

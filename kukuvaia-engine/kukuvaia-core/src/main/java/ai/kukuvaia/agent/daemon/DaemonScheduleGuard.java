package ai.kukuvaia.agent.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prevents cron task overlap — skip-if-running semantics.
 * Covers: Finding #17 (cron overlap when task runs longer than interval).
 */
@Component
public class DaemonScheduleGuard {

    private static final Logger log = LoggerFactory.getLogger(DaemonScheduleGuard.class);

    private final Map<String, AtomicBoolean> runningTasks = new ConcurrentHashMap<>();

    /**
     * Try to acquire execution lock for a daemon task.
     *
     * @param taskName unique daemon task name
     * @return true if lock acquired, false if task is already running
     */
    public boolean tryAcquire(String taskName) {
        AtomicBoolean running = runningTasks.computeIfAbsent(taskName,
                k -> new AtomicBoolean(false));

        if (!running.compareAndSet(false, true)) {
            log.info("Skipping daemon task '{}' — previous execution still running", taskName);
            return false;
        }
        return true;
    }

    /**
     * Release execution lock after daemon task completes.
     */
    public void release(String taskName) {
        AtomicBoolean running = runningTasks.get(taskName);
        if (running != null) {
            running.set(false);
        }
    }

    /**
     * Check if a daemon task is currently running.
     */
    public boolean isRunning(String taskName) {
        AtomicBoolean running = runningTasks.get(taskName);
        return running != null && running.get();
    }
}

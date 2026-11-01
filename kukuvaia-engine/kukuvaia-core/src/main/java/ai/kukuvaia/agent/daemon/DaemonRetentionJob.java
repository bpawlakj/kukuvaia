package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly prune of finished {@code daemon_tasks} rows (security-review #21 retention policy). */
@Component
public class DaemonRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DaemonRetentionJob.class);

    private final DaemonTaskStore store;
    private final DaemonProperties properties;

    public DaemonRetentionJob(DaemonTaskStore store, DaemonProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(cron = "${kukuvaia.daemon.retention-cron:0 30 3 * * *}")
    public void prune() {
        try {
            int deleted = store.pruneOlderThan(properties.getRetentionDays());
            if (deleted > 0) {
                log.info("Daemon retention: deleted {} task rows older than {} days", deleted, properties.getRetentionDays());
            }
        } catch (RuntimeException e) {
            log.error("Daemon retention prune failed: {}", e.getMessage());
        }
    }
}

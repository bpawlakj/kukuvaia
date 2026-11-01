package ai.kukuvaia.agent.daemon.notify;

import ai.kukuvaia.agent.daemon.DaemonTaskResult;
import ai.kukuvaia.agent.daemon.NotificationSanitizer;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sends a finished run's summary to the sinks named in the job's {@code notify:} list.
 *
 * <p>Sinks run on a small bounded executor so a slow receiver never delays the run (its slot and
 * overlap lock are released independently). When the queue is full, notifications are dropped and
 * logged — the audit row and the API remain the source of truth.
 */
@Component
public class DaemonNotifier {

    private static final Logger log = LoggerFactory.getLogger(DaemonNotifier.class);

    private static final int MAX_PENDING = 100;

    private final Map<String, NotificationSink> sinks = new TreeMap<>();
    private final NotificationSanitizer sanitizer;
    private final java.util.concurrent.ThreadPoolExecutor sender = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(MAX_PENDING),
            Thread.ofPlatform().daemon().name("kukuvaia-daemon-notify-", 0).factory(),
            (task, pool) -> log.warn("Daemon notification dropped: {} notifications already pending", MAX_PENDING));

    public DaemonNotifier(List<NotificationSink> sinkBeans, NotificationSanitizer sanitizer) {
        sinkBeans.forEach(s -> sinks.put(s.name(), s));
        this.sanitizer = sanitizer;
        sender.allowCoreThreadTimeOut(true);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        sender.shutdown();
    }

    public java.util.Set<String> sinkNames() {
        return sinks.keySet();
    }

    /** Never throws: notification problems are logged, the run's outcome is unaffected. */
    public void notifyFinished(DaemonJobDefinition job, DaemonTaskResult result) {
        if (result == null || result.status() == DaemonTaskResult.DaemonTaskStatus.SKIPPED) return;
        NotificationSanitizer.NotificationPayload payload;
        try {
            payload = sanitizer.toExternalPayload(result);
        } catch (RuntimeException e) {
            log.error("Could not build notification for task {}: {}", result.taskId(), e.getMessage());
            return;
        }
        for (String name : job.notifications()) {
            NotificationSink sink = sinks.get(name);
            if (sink == null) {
                log.warn("Job '{}' references unknown notification sink '{}' (available: {})", job.name(), name, sinks.keySet());
                continue;
            }
            final NotificationSanitizer.NotificationPayload p = payload;
            sender.execute(() -> {
                try {
                    sink.send(p);
                } catch (Exception e) {
                    log.warn("Notification sink '{}' failed for task {} ('{}'): {}", name, result.taskId(), job.name(), e.getMessage());
                }
            });
        }
    }
}

package ai.kukuvaia.agent.daemon.notify;

import ai.kukuvaia.agent.daemon.NotificationSanitizer.NotificationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default sink: one structured log line per finished run (summary only). */
@Component
public class LogNotificationSink implements NotificationSink {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSink.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void send(NotificationPayload p) {
        log.info("Daemon run finished: job={} task={} status={} trigger={} durationMs={} tokens={} summary=\"{}\"",
                p.name(), p.taskId(), p.status(), p.triggerSource(), p.durationMs(), p.tokenUsage(), p.summary());
    }
}

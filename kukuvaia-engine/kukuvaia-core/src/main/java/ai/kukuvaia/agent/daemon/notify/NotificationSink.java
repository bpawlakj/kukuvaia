package ai.kukuvaia.agent.daemon.notify;

import ai.kukuvaia.agent.daemon.NotificationSanitizer.NotificationPayload;

/**
 * Where daemon run notifications go ({@code notify:} in a job definition). Sinks only ever receive the
 * {@link NotificationPayload} summary (security-review #13); full results stay behind the authenticated
 * {@code /api/daemon/tasks/{id}}. A failing sink never fails the run.
 */
public interface NotificationSink {

    /** Name referenced from YAML, e.g. {@code log}, {@code webhook}. */
    String name();

    void send(NotificationPayload payload) throws Exception;
}

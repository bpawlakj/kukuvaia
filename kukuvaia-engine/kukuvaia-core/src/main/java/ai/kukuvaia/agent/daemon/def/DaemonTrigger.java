package ai.kukuvaia.agent.daemon.def;

/** How a daemon job can be started. */
public sealed interface DaemonTrigger {

    /** Spring cron expression (6 fields), e.g. {@code 0 0 2 * * MON-FRI}. */
    record Cron(String expression) implements DaemonTrigger {}

    /** {@code POST /api/webhooks/{route}}. */
    record Webhook(String route) implements DaemonTrigger {}

    /** PostgreSQL {@code NOTIFY <channel>}. */
    record PgNotify(String channel) implements DaemonTrigger {}

    /** {@code POST /api/daemon/jobs/{name}/run} and in-process callers. */
    record Manual() implements DaemonTrigger {}
}

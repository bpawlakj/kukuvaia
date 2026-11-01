package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonJobRegistry;
import ai.kukuvaia.agent.daemon.def.PromptRenderer;
import ai.kukuvaia.security.PayloadSanitizer;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PostgreSQL {@code LISTEN/NOTIFY} trigger for daemon jobs ({@code triggers: [ { pg-notify: channel } ]}).
 *
 * <p>One dedicated connection listens on every declared channel and reconnects with backoff. Each
 * notification goes to {@link PgNotifyDebouncer} (security-review #16: storms become one batched run).
 * A batch is turned into the job's input the same way as a webhook payload: only the job's declared
 * {@code input.fields} are taken (JSON payloads via {@link PayloadSanitizer#extractFields}; a plain-text
 * payload only if the job declares a {@code payload} field), values of all events are de-duplicated
 * and joined, and {@code batch_size} is provided if declared. Raw payloads never reach a prompt.
 */
@Component
public class PgNotifyListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PgNotifyListener.class);
    /** Same rule as the job parser; LISTEN takes an identifier, not a bind parameter. */
    private static final Pattern CHANNEL = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final int MAX_FIELD_LENGTH = 500;

    private final DataSource dataSource;
    private final DaemonJobRegistry registry;
    private final DaemonRuntime runtime;
    private final PgNotifyDebouncer debouncer;
    private final PayloadSanitizer sanitizer;
    private final DaemonProperties properties;

    private volatile boolean running;
    private volatile Thread listenerThread;

    public PgNotifyListener(DataSource dataSource, DaemonJobRegistry registry, DaemonRuntime runtime,
                            PgNotifyDebouncer debouncer, PayloadSanitizer sanitizer, DaemonProperties properties) {
        this.dataSource = dataSource;
        this.registry = registry;
        this.runtime = runtime;
        this.debouncer = debouncer;
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        Map<String, List<DaemonJobDefinition>> channels = registry.byPgNotifyChannel();
        running = true;
        if (channels.isEmpty() || !properties.isEnabled()) {
            log.info("PG NOTIFY listener idle ({})", channels.isEmpty() ? "no pg-notify triggers" : "daemon disabled");
            return;
        }
        channels.keySet().forEach(ch -> debouncer.register(ch, batch -> onBatch(ch, batch)));
        listenerThread = Thread.ofPlatform().daemon().name("kukuvaia-pg-notify").start(() -> listenLoop(channels.keySet()));
        log.info("PG NOTIFY listener started for channels {}", channels.keySet());
    }

    @Override
    public synchronized void stop() {
        running = false;
        Thread t = listenerThread;
        if (t != null) t.interrupt();
        listenerThread = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listenLoop(Set<String> channels) {
        long backoffMs = 1000;
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                PGConnection pg = connection.unwrap(PGConnection.class);
                try (Statement statement = connection.createStatement()) {
                    for (String channel : channels) {
                        if (!CHANNEL.matcher(channel).matches()) continue; // validated at load; defence in depth
                        statement.execute("LISTEN \"" + channel + "\"");
                    }
                }
                backoffMs = 1000;
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(1000);
                    if (notifications == null) continue;
                    for (PGNotification n : notifications) {
                        debouncer.onNotification(n.getName(), n.getParameter());
                    }
                }
            } catch (SQLException e) {
                if (!running) break;
                log.warn("PG NOTIFY connection lost ({}); reconnecting in {} ms", e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
    }

    /** Turn one debounced batch into one run per job listening on the channel. */
    void onBatch(String channel, List<String> payloads) {
        for (DaemonJobDefinition job : registry.byPgNotifyChannel().getOrDefault(channel, List.of())) {
            Map<String, String> fields = fieldsFor(job, payloads);
            if (!job.matches(fields)) {
                log.debug("PG NOTIFY batch on '{}' does not match job '{}' input filter", channel, job.name());
                continue;
            }
            var submission = runtime.submit(job, new JobRequest(PromptRenderer.render(job.prompt(), fields), fields,
                    "pg-notify:" + channel, null));
            log.info("PG NOTIFY '{}' ({} events) → job '{}': task #{} {}", channel, payloads.size(), job.name(),
                    submission.taskId(), submission.status());
        }
    }

    Map<String, String> fieldsFor(DaemonJobDefinition job, List<String> payloads) {
        Set<String> declared = Set.copyOf(job.inputFields());
        Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        for (String payload : payloads) {
            if (payload == null || payload.isBlank()) continue;
            if (payload.trim().startsWith("{")) {
                sanitizer.extractFields(payload, declared)
                        .forEach((k, v) -> values.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(v));
            } else if (declared.contains("payload")) {
                String value = sanitizer.sanitizeValue(payload);
                if (value != null) values.computeIfAbsent("payload", x -> new LinkedHashSet<>()).add(value);
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            String joined = String.join(", ", v);
            fields.put(k, joined.length() > MAX_FIELD_LENGTH ? joined.substring(0, MAX_FIELD_LENGTH) + "…" : joined);
        });
        if (declared.contains("batch_size")) fields.put("batch_size", Integer.toString(payloads.size()));
        return fields;
    }
}

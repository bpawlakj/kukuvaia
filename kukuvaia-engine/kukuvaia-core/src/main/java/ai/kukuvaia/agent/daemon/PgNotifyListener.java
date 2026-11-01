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
import java.time.Duration;
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
 *
 * <p>The listening connection is borrowed from the application pool for the listener's lifetime —
 * keep {@code spring.datasource.hikari.maximum-pool-size} at 2 or more when pg-notify jobs exist.
 */
@Component
public class PgNotifyListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PgNotifyListener.class);
    /** Same rule as the job parser; LISTEN takes an identifier, not a bind parameter. */
    private static final Pattern CHANNEL = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final int MAX_FIELD_LENGTH = 500;
    private static final Duration STABLE_CONNECTION = Duration.ofSeconds(60);

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
        channels.keySet().forEach(ch -> debouncer.register(ch,
                (PgNotifyDebouncer.BatchHandler) (batch, total) -> onBatch(ch, batch, total)));
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
            long connectedAt = System.nanoTime();
            try (Connection connection = dataSource.getConnection()) {
                PGConnection pg = connection.unwrap(PGConnection.class);
                try (Statement statement = connection.createStatement()) {
                    for (String channel : channels) {
                        if (!CHANNEL.matcher(channel).matches()) continue; // validated at load; defence in depth
                        statement.execute("LISTEN \"" + channel + "\"");
                    }
                }
                connectedAt = System.nanoTime();
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(1000);
                    if (notifications == null) continue;
                    for (PGNotification n : notifications) {
                        debouncer.onNotification(n.getName(), n.getParameter());
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!running) break;
                // Reset the backoff only after a stable connection — a flapping link keeps backing off.
                if (Duration.ofNanos(System.nanoTime() - connectedAt).compareTo(STABLE_CONNECTION) > 0) {
                    backoffMs = 1000;
                }
                if (e instanceof SQLException) {
                    log.warn("PG NOTIFY connection lost ({}); reconnecting in {} ms", e.getMessage(), backoffMs);
                } else {
                    log.error("PG NOTIFY listener error; restarting in {} ms", backoffMs, e);
                }
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
        onBatch(channel, payloads, payloads.size());
    }

    /**
     * @param totalEvents events received in the window, including overflow beyond the buffered payloads
     */
    void onBatch(String channel, List<String> payloads, int totalEvents) {
        for (DaemonJobDefinition job : registry.byPgNotifyChannel().getOrDefault(channel, List.of())) {
            Map<String, String> fields = fieldsFor(job, payloads, totalEvents);
            if (fields == null) {
                log.debug("PG NOTIFY batch on '{}' has no event matching job '{}' input filter", channel, job.name());
                continue;
            }
            var submission = runtime.submit(job, new JobRequest(PromptRenderer.render(job.prompt(), fields), fields,
                    "pg-notify:" + channel, null));
            log.info("PG NOTIFY '{}' ({} events) → job '{}': task #{} {}", channel, payloads.size(), job.name(),
                    submission.taskId(), submission.status());
        }
    }

    /**
     * Aggregate the declared fields of the events that pass the job's {@code input.match} filter
     * (evaluated per event). Returns {@code null} when no event matches.
     */
    Map<String, String> fieldsFor(DaemonJobDefinition job, List<String> payloads, int totalEvents) {
        Set<String> declared = Set.copyOf(job.inputFields());
        Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        int matched = 0;
        for (String payload : payloads) {
            if (payload == null || payload.isBlank()) continue;
            Map<String, String> event;
            if (payload.trim().startsWith("{")) {
                event = sanitizer.extractFields(payload, declared);
            } else if (declared.contains("payload")) {
                String value = sanitizer.sanitizeValue(payload);
                event = value == null ? Map.of() : Map.of("payload", value);
            } else {
                event = Map.of();
            }
            if (!job.matches(event)) continue;
            matched++;
            event.forEach((k, v) -> values.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(v));
        }
        if (matched == 0) return null;
        // Overflow events (beyond the buffered payloads) were not inspected; they only count when
        // the job has no filter — otherwise batch_size is the number of matching buffered events.
        int batchSize = job.inputMatch().isEmpty() ? Math.max(matched, totalEvents) : matched;
        Map<String, String> fields = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            String joined = String.join(", ", v);
            fields.put(k, joined.length() > MAX_FIELD_LENGTH ? joined.substring(0, MAX_FIELD_LENGTH) + "…" : joined);
        });
        if (declared.contains("batch_size")) fields.put("batch_size", Integer.toString(batchSize));
        return fields;
    }
}

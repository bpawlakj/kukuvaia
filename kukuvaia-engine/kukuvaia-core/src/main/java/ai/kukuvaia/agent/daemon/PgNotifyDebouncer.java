package ai.kukuvaia.agent.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Debounces PG NOTIFY events to prevent daemon task storms (security-review #16).
 *
 * <p>Events of one channel are collected until the channel has been quiet for
 * {@code notify-debounce-seconds}, but never longer than {@code notify-max-wait-seconds} after the
 * first event (a steady trickle cannot starve the flush). At most {@code notify-max-batch-size}
 * payloads are buffered; further events in the same window are only counted. So a storm of any size
 * produces one batch per window, and memory stays bounded.
 */
@Component
public class PgNotifyDebouncer {

    private static final Logger log = LoggerFactory.getLogger(PgNotifyDebouncer.class);

    /** Receives one flushed window: the buffered payloads and the total number of events seen. */
    @FunctionalInterface
    public interface BatchHandler {
        void accept(List<String> payloads, int totalEvents);
    }

    @Value("${kukuvaia.daemon.notify-debounce-seconds:5}")
    private int debounceSeconds;

    @Value("${kukuvaia.daemon.notify-max-wait-seconds:30}")
    private int maxWaitSeconds;

    @Value("${kukuvaia.daemon.notify-max-batch-size:100}")
    private int maxBatchSize;

    private final Map<String, BatchAccumulator> accumulators = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pg-notify-debounce");
                t.setDaemon(true);
                return t;
            });

    public void register(String channel, BatchHandler handler) {
        accumulators.put(channel, new BatchAccumulator(channel, handler));
        log.info("Registered debounced PG NOTIFY listener for channel '{}' (window={}s, maxWait={}s, maxBatch={})",
                channel, debounceSeconds, maxWaitSeconds, maxBatchSize);
    }

    public void register(String channel, Consumer<List<String>> consumer) {
        register(channel, (payloads, total) -> consumer.accept(payloads));
    }

    /** Accept a notification; buffers it and (re)schedules the flush. */
    public void onNotification(String channel, String payload) {
        BatchAccumulator acc = accumulators.get(channel);
        if (acc == null) {
            log.warn("Received NOTIFY for unregistered channel '{}'", channel);
            return;
        }
        acc.add(payload);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        accumulators.values().forEach(BatchAccumulator::flushNow);
    }

    private class BatchAccumulator {
        private final String channel;
        private final BatchHandler handler;
        private final List<String> buffer = new ArrayList<>();
        private int total;
        private long firstEventNanos;
        private ScheduledFuture<?> pendingFlush;

        BatchAccumulator(String channel, BatchHandler handler) {
            this.channel = channel;
            this.handler = handler;
        }

        synchronized void add(String payload) {
            if (total == 0) firstEventNanos = System.nanoTime();
            total++;
            if (buffer.size() < Math.max(1, maxBatchSize)) {
                buffer.add(payload);
            } else if (total == maxBatchSize + 1) {
                log.warn("PG NOTIFY '{}': more than {} events in one window — further payloads are counted only",
                        channel, maxBatchSize);
            }
            if (pendingFlush != null) pendingFlush.cancel(false);
            long sinceFirstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstEventNanos);
            // A max-wait below the window (or 0) must never disable debouncing.
            long maxWaitMs = TimeUnit.SECONDS.toMillis(Math.max(maxWaitSeconds, debounceSeconds));
            long untilMaxWaitMs = Math.max(0, maxWaitMs - sinceFirstMs);
            long delayMs = Math.min(TimeUnit.SECONDS.toMillis(debounceSeconds), untilMaxWaitMs);
            if (scheduler.isShutdown()) return;
            pendingFlush = scheduler.schedule(this::flushNow, delayMs, TimeUnit.MILLISECONDS);
        }

        synchronized void flushNow() {
            if (total == 0) return;
            List<String> batch = new ArrayList<>(buffer);
            int events = total;
            buffer.clear();
            total = 0;
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
                pendingFlush = null;
            }
            log.info("Flushing PG NOTIFY batch on '{}': {} events ({} buffered)", channel, events, batch.size());
            try {
                handler.accept(batch, events);
            } catch (Exception e) {
                log.error("Error processing PG NOTIFY batch on '{}'", channel, e);
            }
        }
    }
}

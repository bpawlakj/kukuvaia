package ai.kukuvaia.agent.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Debounces PG NOTIFY events to prevent daemon task storms.
 * Covers: Finding #16 (PG NOTIFY storm → daemon task flood).
 *
 * Batches events from the same channel over a configurable window,
 * then fires a single daemon task with the aggregated payload.
 */
@Component
public class PgNotifyDebouncer {

    private static final Logger log = LoggerFactory.getLogger(PgNotifyDebouncer.class);

    @Value("${kukuvaia.daemon.notify-debounce-seconds:5}")
    private int debounceSeconds;

    @Value("${kukuvaia.daemon.notify-max-batch-size:100}")
    private int maxBatchSize;

    private final Map<String, BatchAccumulator> accumulators = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pg-notify-debounce");
                t.setDaemon(true);
                return t;
            });

    /**
     * Register a channel listener with debouncing.
     *
     * @param channel  PG NOTIFY channel name
     * @param consumer called with batched payloads after debounce window
     */
    public void register(String channel, Consumer<List<String>> consumer) {
        accumulators.put(channel, new BatchAccumulator(consumer));
        log.info("Registered debounced PG NOTIFY listener for channel '{}' " +
                "(window={}s, maxBatch={})", channel, debounceSeconds, maxBatchSize);
    }

    /**
     * Accept a notification from PG NOTIFY.
     * Buffers the payload and schedules flush after debounce window.
     */
    public void onNotification(String channel, String payload) {
        BatchAccumulator acc = accumulators.get(channel);
        if (acc == null) {
            log.warn("Received NOTIFY for unregistered channel '{}'", channel);
            return;
        }
        acc.add(payload);
    }

    public void shutdown() {
        scheduler.shutdown();
        // Flush remaining batches
        accumulators.values().forEach(BatchAccumulator::flushNow);
    }

    private class BatchAccumulator {
        private final Consumer<List<String>> consumer;
        private final List<String> buffer = new ArrayList<>();
        private ScheduledFuture<?> pendingFlush;

        BatchAccumulator(Consumer<List<String>> consumer) {
            this.consumer = consumer;
        }

        synchronized void add(String payload) {
            buffer.add(payload);

            // If batch is full, flush immediately
            if (buffer.size() >= maxBatchSize) {
                log.info("PG NOTIFY batch full ({} events), flushing immediately",
                        maxBatchSize);
                flushNow();
                return;
            }

            // Schedule flush after debounce window (reset on each new event)
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
            }
            pendingFlush = scheduler.schedule(this::flushNow,
                    debounceSeconds, TimeUnit.SECONDS);
        }

        synchronized void flushNow() {
            if (buffer.isEmpty()) return;
            List<String> batch = new ArrayList<>(buffer);
            buffer.clear();
            if (pendingFlush != null) {
                pendingFlush.cancel(false);
                pendingFlush = null;
            }
            log.info("Flushing PG NOTIFY batch: {} events", batch.size());
            try {
                consumer.accept(batch);
            } catch (Exception e) {
                log.error("Error processing PG NOTIFY batch", e);
            }
        }
    }
}

package ai.kukuvaia.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes active session count as a Micrometer gauge backed by a cached value.
 * The DB count is refreshed on a fixed schedule so each Prometheus scrape reads
 * the cached AtomicLong rather than hitting PostgreSQL. See P01 review §7.
 */
@Component
public class SessionMetrics {

    private static final Logger log = LoggerFactory.getLogger(SessionMetrics.class);

    private final JdbcTemplate jdbc;
    private final AtomicLong activeSessionCount = new AtomicLong(0);

    public SessionMetrics(MeterRegistry meterRegistry, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        Gauge.builder("kukuvaia.session.active", activeSessionCount, AtomicLong::get)
                .description("Active sessions in kukuvaia.sessions (cached, refreshed every 30s)")
                .register(meterRegistry);
    }

    @PostConstruct
    void initialRefresh() {
        refresh();
    }

    @Scheduled(fixedRate = 30_000L)
    void refresh() {
        try {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM kukuvaia.sessions WHERE status = 'active'",
                    Long.class);
            activeSessionCount.set(count != null ? count : 0L);
        } catch (Exception e) {
            log.debug("Session metrics refresh failed: {}", e.getMessage());
        }
    }
}

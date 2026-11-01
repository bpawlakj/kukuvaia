package ai.kukuvaia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Rate limits webhook requests per source IP using sliding window.
 * Covers: Finding #10 (webhook flood → cost explosion).
 *
 * Runs before WebhookAuthFilter to reject floods before HMAC computation.
 */
@Component
@Order(1) // Run before auth filter
public class WebhookRateLimiter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookRateLimiter.class);

    private static final long WINDOW_MILLIS = 60_000;
    private static final long CAPACITY_CLEANUP_INTERVAL_MILLIS = 1_000;

    private final int maxPerMinute;
    private final int maxTrackedSources;
    private final Clock clock;
    private final Map<String, WindowCounter> counters = new HashMap<>();
    private long lastCleanup;

    @Autowired
    public WebhookRateLimiter(
            @Value("${kukuvaia.webhooks.rate-limit-per-minute:10}") int maxPerMinute,
            @Value("${kukuvaia.webhooks.max-tracked-sources:10000}") int maxTrackedSources) {
        this(maxPerMinute, maxTrackedSources, Clock.systemUTC());
    }

    WebhookRateLimiter(int maxPerMinute, int maxTrackedSources, Clock clock) {
        if (maxPerMinute <= 0 || maxTrackedSources <= 0) {
            throw new IllegalArgumentException("Webhook rate limits must be positive");
        }
        this.maxPerMinute = maxPerMinute;
        this.maxTrackedSources = maxTrackedSources;
        this.clock = clock;
        this.lastCleanup = clock.millis();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/api/webhooks") && !path.startsWith("/api/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        // Forwarding headers are untrusted input. A trusted proxy/container may normalize
        // remoteAddr before this filter; this filter must never parse caller-supplied XFF.
        if (!tryAcquire(request.getRemoteAddr())) {
            log.debug("Webhook rate limit exceeded");
            response.setHeader("Retry-After", "60");
            response.sendError(429, "Rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private synchronized boolean tryAcquire(String clientIp) {
        long now = clock.millis();
        long cutoff = now - WINDOW_MILLIS;
        long cleanupInterval = counters.size() >= maxTrackedSources
                ? CAPACITY_CLEANUP_INTERVAL_MILLIS : WINDOW_MILLIS;
        if (now - lastCleanup >= cleanupInterval) {
            counters.values().removeIf(counter -> counter.expired(cutoff));
            lastCleanup = now;
        }

        WindowCounter counter = counters.get(clientIp);
        if (counter == null) {
            // Do not evict active sources: that would let a flood reset their quotas.
            if (counters.size() >= maxTrackedSources) {
                return false;
            }
            counter = new WindowCounter();
            counters.put(clientIp, counter);
        }
        return counter.tryAcquire(maxPerMinute, now, cutoff);
    }

    /** Accessed only under the limiter's lock; each queue holds at most maxPerMinute entries. */
    private static class WindowCounter {
        private final Deque<Long> acceptedRequests = new ArrayDeque<>();

        boolean expired(long cutoff) {
            return acceptedRequests.isEmpty() || acceptedRequests.peekLast() <= cutoff;
        }

        boolean tryAcquire(int limit, long now, long cutoff) {
            while (!acceptedRequests.isEmpty() && acceptedRequests.peekFirst() <= cutoff) {
                acceptedRequests.removeFirst();
            }
            if (acceptedRequests.size() >= limit) {
                return false;
            }
            acceptedRequests.addLast(now);
            return true;
        }
    }
}

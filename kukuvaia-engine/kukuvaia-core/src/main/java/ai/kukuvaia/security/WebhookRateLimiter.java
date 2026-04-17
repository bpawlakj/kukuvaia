package ai.kukuvaia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Value("${kukuvaia.webhooks.rate-limit-per-minute:10}")
    private int maxPerMinute;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/webhooks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = resolveClientIp(request);
        WindowCounter counter = counters.computeIfAbsent(clientIp,
                k -> new WindowCounter());

        if (!counter.tryAcquire(maxPerMinute)) {
            log.warn("Webhook rate limit exceeded for IP {}: {}/min",
                    clientIp, maxPerMinute);
            response.setHeader("Retry-After", "60");
            response.sendError(429, "Rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Simple sliding window counter. Resets every minute.
     * Thread-safe via AtomicInteger + volatile.
     */
    static class WindowCounter {
        private volatile long windowStart = Instant.now().getEpochSecond() / 60;
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryAcquire(int limit) {
            long currentWindow = Instant.now().getEpochSecond() / 60;
            if (currentWindow != windowStart) {
                synchronized (this) {
                    if (currentWindow != windowStart) {
                        windowStart = currentWindow;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet() <= limit;
        }
    }
}

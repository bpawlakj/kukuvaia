package ai.kukuvaia.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookRateLimiterTest {

    private final Clock clock = mock(Clock.class);

    @Test
    void doFilter_spoofedForwardingHeaderDoesNotResetQuota() throws Exception {
        var limiter = new WebhookRateLimiter(1, 10, clock);

        assertThat(request(limiter, "192.0.2.1", "198.51.100.1").getStatus()).isEqualTo(200);
        var rejected = request(limiter, "192.0.2.1", "198.51.100.2");

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(request(limiter, "192.0.2.2", "198.51.100.1").getStatus()).isEqualTo(200);
    }

    @Test
    void doFilter_slidingWindowDoesNotResetAtMinuteBoundary() throws Exception {
        when(clock.millis()).thenReturn(59_000L);
        var limiter = new WebhookRateLimiter(1, 10, clock);
        assertThat(request(limiter, "192.0.2.1", null).getStatus()).isEqualTo(200);

        when(clock.millis()).thenReturn(60_000L);
        assertThat(request(limiter, "192.0.2.1", null).getStatus()).isEqualTo(429);
        when(clock.millis()).thenReturn(118_999L);
        assertThat(request(limiter, "192.0.2.1", null).getStatus()).isEqualTo(429);
        when(clock.millis()).thenReturn(119_000L);
        assertThat(request(limiter, "192.0.2.1", null).getStatus()).isEqualTo(200);
    }

    @Test
    void doFilter_capacityFailsClosedAndExpiredSourcesAreReclaimed() throws Exception {
        var limiter = new WebhookRateLimiter(2, 2, clock);
        assertThat(request(limiter, "192.0.2.1", null).getStatus()).isEqualTo(200);
        assertThat(request(limiter, "192.0.2.2", null).getStatus()).isEqualTo(200);
        assertThat(request(limiter, "192.0.2.3", null).getStatus()).isEqualTo(429);
        // A full map must not prevent an already tracked source using its remaining quota.
        assertThat(request(limiter, "192.0.2.1", null).getStatus()).isEqualTo(200);

        when(clock.millis()).thenReturn(60_000L);
        assertThat(request(limiter, "192.0.2.3", null).getStatus()).isEqualTo(200);
    }

    @Test
    void doFilter_parallelRequestsShareOneQuota() throws Exception {
        var limiter = new WebhookRateLimiter(10, 10, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var ready = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    ready.await();
                    if (request(limiter, "192.0.2.1", null).getStatus() == 200) {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.countDown();
        }
        assertThat(accepted).hasValue(10);
    }

    @Test
    void doFilter_nonWebhookRequestsBypassLimiter() throws Exception {
        var limiter = new WebhookRateLimiter(1, 1, clock);
        for (int i = 0; i < 3; i++) {
            var request = new MockHttpServletRequest("GET", "/api/sessions");
            var response = new MockHttpServletResponse();
            limiter.doFilter(request, response, (req, res) -> res.getWriter().write("ok"));
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("ok");
        }
    }

    private MockHttpServletResponse request(WebhookRateLimiter limiter, String peer, String forwarded)
            throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/webhooks/ci");
        request.setRemoteAddr(peer);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        var response = new MockHttpServletResponse();
        limiter.doFilter(request, response, (req, res) -> res.getWriter().write("ok"));
        if (response.getStatus() == 200) assertThat(response.getContentAsString()).isEqualTo("ok");
        return response;
    }
}

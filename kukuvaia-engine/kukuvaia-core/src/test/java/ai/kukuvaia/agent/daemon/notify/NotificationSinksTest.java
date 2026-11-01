package ai.kukuvaia.agent.daemon.notify;

import ai.kukuvaia.agent.daemon.DaemonTaskResult;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.NotificationSanitizer;
import ai.kukuvaia.agent.daemon.NotificationSanitizer.NotificationPayload;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonTrigger;
import ai.kukuvaia.agent.daemon.def.Overlap;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Notification sinks — summary-only payload, signed webhook, failures isolated")
class NotificationSinksTest {

    private static final String FULL_RESULT = "Root cause: flaky test in OrderServiceTest\n"
            + "SECRET-DETAIL line two that must never leave via notifications";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    static DaemonTaskResult result(DaemonTaskStatus status, String text, String error) {
        return new DaemonTaskResult(42, "ci-failure-analysis", "analyst", "daemon", status, text, error,
                1234, 900, "webhook:ci", Instant.now(), Instant.now());
    }

    static DaemonJobDefinition job(List<String> sinks) {
        return new DaemonJobDefinition("ci-failure-analysis", "", JobKind.SPECIALIST, "analyst", List.of(), "p",
                List.of(new DaemonTrigger.Manual()), null, JobLimits.defaults(), Overlap.skip(), sinks, true, "test");
    }

    @Test
    @DisplayName("payload carries the first-line summary only, never the full result; secrets redacted")
    void payloadIsSummaryOnly() {
        NotificationPayload p = new NotificationSanitizer().toExternalPayload(result(DaemonTaskStatus.COMPLETED, FULL_RESULT, null));

        assertThat(p.summary()).isEqualTo("Root cause: flaky test in OrderServiceTest");
        assertThat(p.toString()).doesNotContain("SECRET-DETAIL");

        NotificationPayload failed = new NotificationSanitizer().toExternalPayload(result(DaemonTaskStatus.FAILED, null,
                "connect failed jdbc:postgresql://db.internal:5432/k?password=hunter2"));
        assertThat(failed.summary()).doesNotContain("hunter2").doesNotContain("db.internal");
    }

    @Test
    @DisplayName("webhook sink POSTs JSON whose HMAC-SHA256 signature verifies with the shared secret")
    void webhookSignatureVerifies() throws Exception {
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            body.set(ex.getRequestBody().readAllBytes());
            signature.set(ex.getRequestHeaders().getFirst(WebhookNotificationSink.SIGNATURE_HEADER));
            timestamp.set(ex.getRequestHeaders().getFirst(WebhookNotificationSink.TIMESTAMP_HEADER));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        var sink = new WebhookNotificationSink("http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                "s3cret", 2, JsonMapper.builder().build());

        sink.send(new NotificationSanitizer().toExternalPayload(result(DaemonTaskStatus.COMPLETED, FULL_RESULT, null)));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        // Receiver-side verification: HMAC over "<timestamp>.<body>", timestamp recent.
        mac.update((timestamp.get() + ".").getBytes(StandardCharsets.UTF_8));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.get()));
        assertThat(signature.get()).isEqualTo(expected);
        assertThat(Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp.get()))).isLessThan(60);
        String json = new String(body.get(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"taskId\":42").contains("Root cause").doesNotContain("SECRET-DETAIL");
    }

    @Test
    @DisplayName("webhook sink: non-2xx is an error; plain http only to localhost; no URL or no secret = inactive, nothing sent")
    void webhookPolicies() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
        server.start();
        var mapper = JsonMapper.builder().build();
        var failing = new WebhookNotificationSink("http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "s", 2, mapper);
        var payload = new NotificationSanitizer().toExternalPayload(result(DaemonTaskStatus.COMPLETED, "x", null));

        assertThatThrownBy(() -> failing.send(payload)).hasMessageContaining("HTTP 500");
        assertThatThrownBy(() -> new WebhookNotificationSink("http://hooks.example.com/x", "s", 2, mapper))
                .hasMessageContaining("must be https");
        var unconfigured = new WebhookNotificationSink("", "", 2, mapper);
        assertThat(unconfigured.isConfigured()).isFalse();
        unconfigured.send(payload); // no-op, no exception

        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/unsigned", ex -> { hits.incrementAndGet(); ex.sendResponseHeaders(204, -1); ex.close(); });
        var noSecret = new WebhookNotificationSink("http://127.0.0.1:" + server.getAddress().getPort() + "/unsigned", "", 2, mapper);
        assertThat(noSecret.isConfigured()).isFalse();
        noSecret.send(payload);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("notifier: failing and unknown sinks never throw; other sinks still receive; SKIPPED not notified")
    void notifierIsolatesFailures() throws Exception {
        List<NotificationPayload> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        NotificationSink boom = new NotificationSink() {
            public String name() { return "webhook"; }
            public void send(NotificationPayload p) { throw new IllegalStateException("down"); }
        };
        NotificationSink capture = new NotificationSink() {
            public String name() { return "log"; }
            public void send(NotificationPayload p) { received.add(p); }
        };
        var notifier = new DaemonNotifier(List.of(boom, capture), new NotificationSanitizer());

        notifier.notifyFinished(job(List.of("webhook", "nope", "log")), result(DaemonTaskStatus.COMPLETED, FULL_RESULT, null));
        notifier.notifyFinished(job(List.of("log")), result(DaemonTaskStatus.SKIPPED, null, "budget"));

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(received).hasSize(1));
        Thread.sleep(200); // the SKIPPED run must not arrive late either
        assertThat(received).singleElement().extracting(NotificationPayload::taskId).isEqualTo(42L);
    }
}

package ai.kukuvaia.agent.daemon.notify;

import ai.kukuvaia.agent.daemon.NotificationSanitizer.NotificationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Posts the notification summary as JSON to a configured URL, signed with a shared secret:
 * {@code X-Kukuvaia-Timestamp: <epoch seconds>} and
 * {@code X-Kukuvaia-Signature-256: sha256=<hex HMAC-SHA256(secret, "<timestamp>.<body>")>} — the
 * receiver verifies the signature and rejects stale timestamps (replay protection).
 *
 * <p>URL and secret are server configuration ({@code kukuvaia.daemon.notifications.webhook.*}) — never
 * taken from a job definition or its input. Only {@code https} URLs are accepted (plain {@code http} only
 * for localhost). Both URL and secret are required — without either the sink is inactive (fail closed:
 * nothing is ever sent unsigned).
 */
@Component
public class WebhookNotificationSink implements NotificationSink {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationSink.class);
    static final String SIGNATURE_HEADER = "X-Kukuvaia-Signature-256";
    static final String TIMESTAMP_HEADER = "X-Kukuvaia-Timestamp";

    private final URI url;
    private final String secret;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public WebhookNotificationSink(@Value("${kukuvaia.daemon.notifications.webhook.url:}") String url,
                                   @Value("${kukuvaia.daemon.notifications.webhook.secret:}") String secret,
                                   @Value("${kukuvaia.daemon.notifications.webhook.timeout-seconds:5}") int timeoutSeconds,
                                   ObjectMapper objectMapper) {
        this.url = parseUrl(url);
        this.secret = secret == null ? "" : secret;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        if (this.url != null && this.secret.isBlank()) {
            log.error("kukuvaia.daemon.notifications.webhook.url is set but the secret is empty — webhook sink inactive");
        }
    }

    @Override
    public String name() {
        return "webhook";
    }

    public boolean isConfigured() {
        return url != null && !secret.isBlank();
    }

    @Override
    public void send(NotificationPayload payload) throws Exception {
        if (!isConfigured()) {
            log.debug("Webhook notification for task {} skipped: webhook sink not configured (url + secret)", payload.taskId());
            return;
        }
        byte[] body = objectMapper.writeValueAsBytes(payload);
        String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(TIMESTAMP_HEADER, timestamp)
                .header(SIGNATURE_HEADER, "sha256=" + sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("webhook notification returned HTTP " + response.statusCode());
        }
    }

    static String sign(String secret, String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static URI parseUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        URI uri = URI.create(raw.trim());
        String host = uri.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && local)) {
            throw new IllegalArgumentException("kukuvaia.daemon.notifications.webhook.url must be https (http only for localhost)");
        }
        return uri;
    }
}

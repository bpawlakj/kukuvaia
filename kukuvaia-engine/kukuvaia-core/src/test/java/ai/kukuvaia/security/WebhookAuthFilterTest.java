package ai.kukuvaia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookAuthFilterTest {

    private static final String SECRET = "test-secret";
    private static final String TOKEN = "test-token";
    private static final String BODY = "{\"event\":\"push\"}";

    /** Records whether the downstream chain (i.e. the controller) ran, and what body it saw. */
    private static final class RecordingChain implements FilterChain {
        final AtomicReference<String> bodySeen = new AtomicReference<>();
        boolean invoked;

        @Override
        public void doFilter(ServletRequest request, jakarta.servlet.ServletResponse response)
                throws java.io.IOException {
            invoked = true;
            bodySeen.set(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static MockHttpServletRequest webhook(String body) {
        var request = new MockHttpServletRequest("POST", "/api/webhooks/github");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("valid HMAC: chain runs and receives the original body")
    void validSignature_passesBodyThrough() throws Exception {
        var filter = new WebhookAuthFilter(SECRET, "", true);
        var request = webhook(BODY);
        request.addHeader("X-Hub-Signature-256", sign(BODY, SECRET));
        var response = new MockHttpServletResponse();
        var chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.bodySeen.get()).isEqualTo(BODY);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("invalid HMAC: rejected with 401 and the controller never runs")
    void invalidSignature_rejectedBeforeController() throws Exception {
        var filter = new WebhookAuthFilter(SECRET, "", true);
        var request = webhook(BODY);
        request.addHeader("X-Hub-Signature-256", sign(BODY, "wrong-secret"));
        var response = new MockHttpServletResponse();
        var chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("empty body with a bogus signature is rejected (no empty-body bypass)")
    void emptyBodyBogusSignature_rejected() throws Exception {
        var filter = new WebhookAuthFilter(SECRET, "", true);
        var request = webhook("");
        request.addHeader("X-Hub-Signature-256", "sha256=deadbeef");
        var response = new MockHttpServletResponse();
        var chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("payload larger than the limit is rejected with 413")
    void oversizedPayload_rejected() throws Exception {
        var filter = new WebhookAuthFilter(SECRET, "", true);
        var request = webhook(BODY);
        request.setContent(new byte[WebhookAuthFilter.MAX_BODY_BYTES + 1]);
        request.addHeader("X-Hub-Signature-256", "sha256=00");
        var response = new MockHttpServletResponse();
        var chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    @Test
    @DisplayName("correct bearer token passes; wrong token is rejected")
    void bearerToken() throws Exception {
        var filter = new WebhookAuthFilter("", TOKEN, true);

        var ok = webhook(BODY);
        ok.addHeader("Authorization", "Bearer " + TOKEN);
        var okChain = new RecordingChain();
        filter.doFilter(ok, new MockHttpServletResponse(), okChain);
        assertThat(okChain.invoked).isTrue();

        var bad = webhook(BODY);
        bad.addHeader("Authorization", "Bearer " + TOKEN + "x");
        var badResponse = new MockHttpServletResponse();
        var badChain = new RecordingChain();
        filter.doFilter(bad, badResponse, badChain);
        assertThat(badChain.invoked).isFalse();
        assertThat(badResponse.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    @DisplayName("no credentials: 401; webhooks disabled: 404")
    void noCredentialsAndDisabled() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new RecordingChain();
        new WebhookAuthFilter(SECRET, TOKEN, true).doFilter(webhook(BODY), response, chain);
        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        var disabledResponse = new MockHttpServletResponse();
        new WebhookAuthFilter(SECRET, TOKEN, false).doFilter(webhook(BODY), disabledResponse, chain);
        assertThat(disabledResponse.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }
}

package ai.kukuvaia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Validates webhook requests using HMAC-SHA256 signature verification.
 * Covers: Finding #4 (unauthenticated webhook endpoints).
 *
 * <p>Supports two auth modes:
 * <ul>
 *   <li>HMAC signature: {@code X-Hub-Signature-256} header (GitHub-style)</li>
 *   <li>Bearer token: {@code Authorization: Bearer {token}} (simple API key)</li>
 * </ul>
 *
 * <p>Both checks complete <em>before</em> the request reaches the controller, and both use
 * constant-time comparison. For HMAC the body is read (bounded by {@link #MAX_BODY_BYTES}),
 * verified, and then replayed to the rest of the chain.
 */
@Component
public class WebhookAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookAuthFilter.class);
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HMAC_ALGO = "HmacSHA256";

    /** 25 MiB mirrors GitHub's maximum webhook payload size. Larger bodies are rejected (413). */
    static final int MAX_BODY_BYTES = 25 * 1024 * 1024;

    private final String webhookSecret;
    private final String bearerToken;
    private final boolean webhooksEnabled;

    public WebhookAuthFilter(@Value("${kukuvaia.webhooks.secret:}") String webhookSecret,
                             @Value("${kukuvaia.webhooks.bearer-token:}") String bearerToken,
                             @Value("${kukuvaia.webhooks.enabled:true}") boolean webhooksEnabled) {
        this.webhookSecret = webhookSecret;
        this.bearerToken = bearerToken;
        this.webhooksEnabled = webhooksEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/webhooks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        if (!webhooksEnabled) {
            log.warn("Webhook received but webhooks are disabled");
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Bearer token first (simpler) — constant-time comparison.
        if (isConfigured(bearerToken) && bearerMatches(request.getHeader("Authorization"))) {
            // Bounded like the HMAC path — the controller must never read an unbounded body.
            byte[] body = readBodyBounded(request);
            if (body == null) {
                log.warn("Webhook payload from {} exceeds {} bytes — rejected", request.getRemoteAddr(), MAX_BODY_BYTES);
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Webhook payload too large");
                return;
            }
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }

        // HMAC signature — verified BEFORE the request reaches the controller.
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (isConfigured(webhookSecret) && signature != null) {
            byte[] body = readBodyBounded(request);
            if (body == null) {
                log.warn("Webhook payload from {} exceeds {} bytes — rejected",
                        request.getRemoteAddr(), MAX_BODY_BYTES);
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "Webhook payload too large");
                return;
            }
            if (!verifySignature(body, signature)) {
                log.warn("Webhook HMAC verification failed from {}", request.getRemoteAddr());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid webhook signature");
                return;
            }
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }

        // No valid auth provided
        log.warn("Unauthorized webhook attempt from {}: no valid signature or token",
                request.getRemoteAddr());
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                "Webhook authentication required");
    }

    private static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    private boolean bearerMatches(String authHeader) {
        if (authHeader == null) return false;
        return MessageDigest.isEqual(
                authHeader.getBytes(StandardCharsets.UTF_8),
                (BEARER_PREFIX + bearerToken).getBytes(StandardCharsets.UTF_8));
    }

    /** Reads the whole body, or returns {@code null} if it exceeds {@link #MAX_BODY_BYTES}. */
    private static byte[] readBodyBounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) return null;
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }

    private boolean verifySignature(byte[] payload, String signatureHeader) {
        if (!signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        String receivedHex = signatureHeader.substring(SIGNATURE_PREFIX.length());
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] computed = mac.doFinal(payload);
            String computedHex = HexFormat.of().formatHex(computed);
            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    receivedHex.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC computation failed", e);
            return false;
        }
    }

    /** Replays an already-verified body to downstream filters and the controller. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async read not supported for cached webhook body");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}

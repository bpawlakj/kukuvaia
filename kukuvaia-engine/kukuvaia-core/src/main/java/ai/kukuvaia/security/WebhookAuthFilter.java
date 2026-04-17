package ai.kukuvaia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Validates webhook requests using HMAC-SHA256 signature verification.
 * Covers: Finding #4 (unauthenticated webhook endpoints).
 *
 * Supports two auth modes:
 * - HMAC signature: X-Hub-Signature-256 header (GitHub-style)
 * - Bearer token: Authorization: Bearer {token} (simple API key)
 */
@Component
public class WebhookAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookAuthFilter.class);
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String HMAC_ALGO = "HmacSHA256";

    @Value("${kukuvaia.webhooks.secret:}")
    private String webhookSecret;

    @Value("${kukuvaia.webhooks.bearer-token:}")
    private String bearerToken;

    @Value("${kukuvaia.webhooks.enabled:true}")
    private boolean webhooksEnabled;

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

        // Try bearer token first (simpler)
        String authHeader = request.getHeader("Authorization");
        if (bearerToken != null && !bearerToken.isBlank()) {
            if (authHeader != null && authHeader.equals("Bearer " + bearerToken)) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        // Try HMAC signature
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (webhookSecret != null && !webhookSecret.isBlank() && signature != null) {
            var wrappedRequest = new ContentCachingRequestWrapper(request);
            // Read body to compute HMAC — must wrap request to allow re-reading
            filterChain.doFilter(wrappedRequest, response);

            byte[] body = wrappedRequest.getContentAsByteArray();
            if (body.length > 0 && !verifySignature(body, signature)) {
                log.warn("Webhook HMAC verification failed from {}",
                        request.getRemoteAddr());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "Invalid webhook signature");
                return;
            }
            return;
        }

        // No valid auth provided
        log.warn("Unauthorized webhook attempt from {}: no valid signature or token",
                request.getRemoteAddr());
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                "Webhook authentication required");
    }

    private boolean verifySignature(byte[] payload, String signatureHeader) {
        if (!signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String receivedHex = signatureHeader.substring("sha256=".length());
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
}

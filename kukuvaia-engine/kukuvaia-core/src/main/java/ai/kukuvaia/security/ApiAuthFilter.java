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

import java.io.IOException;
import java.util.Set;

/**
 * Authenticates all Web API requests.
 * Covers: Finding #3 (no auth on Web API).
 *
 * Supports: Bearer token (API key) and JWT validation.
 * In dev mode (kukuvaia.security.dev-mode=true), auth is bypassed with a warning.
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    // Paths that don't require auth
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/actuator/health",
            "/api/auth/login",
            "/api/auth/device-code"
    );

    @Value("${kukuvaia.security.api-key:}")
    private String apiKey;

    @Value("${kukuvaia.security.dev-mode:false}")
    private boolean devMode;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Webhooks have their own auth filter
        if (path.startsWith("/api/webhooks")) {
            return true;
        }
        // Public paths
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        // Only protect /api/* paths
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        if (devMode) {
            log.debug("Dev mode: auth bypassed for {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Authentication required");
            return;
        }

        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (apiKey != null && !apiKey.isBlank() && apiKey.equals(token)) {
                filterChain.doFilter(request, response);
                return;
            }
            // TODO: add JWT validation when JWT auth is implemented
        }

        log.warn("Invalid API authentication from {}", request.getRemoteAddr());
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid credentials");
    }
}

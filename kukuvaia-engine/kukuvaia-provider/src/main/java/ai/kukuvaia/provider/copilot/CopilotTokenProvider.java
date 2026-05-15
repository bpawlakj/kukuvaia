package ai.kukuvaia.provider.copilot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import ai.kukuvaia.provider.service.ChatModelCache;

/**
 * GitHub Copilot authentication and token-refresh provider.
 *
 * <p>Two-stage flow:
 * <ol>
 *   <li><b>OAuth device flow</b> — long-lived {@code gho_xxx} GitHub OAuth token,
 *       acquired once and persisted in {@link CopilotCredentialsStore} (chmod 600).</li>
 *   <li><b>Copilot bearer exchange</b> — short-lived ({@code ~25 min}) Copilot API
 *       token, kept in-memory, auto-refreshed before expiry. Never persisted.</li>
 * </ol>
 *
 * <p>On every successful refresh a {@link CopilotTokenRefreshedEvent} is published so
 * that the {@code ChatModelCache} can invalidate any cached {@code OpenAiApi} bound to
 * the old bearer string.
 *
 * <p>Endpoints (Copilot uses GitHub's public client ID — same one VS Code and JetBrains
 * use):
 * <pre>
 *   POST  github.com/login/device/code                — start device flow
 *   POST  github.com/login/oauth/access_token         — poll for OAuth token
 *   GET   api.github.com/copilot_internal/v2/token    — exchange for Copilot bearer
 *   GET   api.github.com/user                         — resolve GitHub login (for /login status)
 * </pre>
 *
 * <p>The Copilot internal endpoint is unsanctioned for daemon use; see
 * {@code docs/architecture/auth-and-providers.md}. Daemon traffic must continue to use
 * SmartGate.
 */
@Component
public class CopilotTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(CopilotTokenProvider.class);

    /** TCP connect timeout. Kept short so a bad IP from DNS round-robin doesn't stall a chat turn for 15s. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** End-to-end request timeout (covers TLS + response). */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** Retry budget for connect-level failures — helps when GitHub's DNS rotation contains one bad LB IP. */
    private static final int CONNECT_RETRY_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

    private static final Duration REFRESH_HEADROOM = Duration.ofMinutes(2);
    private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    private final CopilotCredentialsStore credentialsStore;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final String clientId;
    private final String deviceCodeUrl;
    private final String accessTokenUrl;
    private final String copilotTokenUrl;
    private final String githubUserUrl;
    private final String scope;

    private HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    private final AtomicReference<DeviceAuthSession> pendingSession = new AtomicReference<>();
    private final AtomicReference<CopilotToken> cachedToken = new AtomicReference<>();
    private volatile ScheduledFuture<?> refreshTask;

    public CopilotTokenProvider(
            CopilotCredentialsStore credentialsStore,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            @Value("${kukuvaia.providers.copilot.client-id:Iv1.b507a08c87ecfe98}") String clientId,
            @Value("${kukuvaia.providers.copilot.scope:read:user}") String scope,
            @Value("${kukuvaia.providers.copilot.device-code-url:https://github.com/login/device/code}") String deviceCodeUrl,
            @Value("${kukuvaia.providers.copilot.access-token-url:https://github.com/login/oauth/access_token}") String accessTokenUrl,
            @Value("${kukuvaia.providers.copilot.copilot-token-url:https://api.github.com/copilot_internal/v2/token}") String copilotTokenUrl,
            @Value("${kukuvaia.providers.copilot.user-url:https://api.github.com/user}") String githubUserUrl) {
        this.credentialsStore = credentialsStore;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.clientId = clientId;
        this.scope = scope;
        this.deviceCodeUrl = deviceCodeUrl;
        this.accessTokenUrl = accessTokenUrl;
        this.copilotTokenUrl = copilotTokenUrl;
        this.githubUserUrl = githubUserUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "copilot-token-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    /** Visible for testing — inject a mock HttpClient. */
    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @PreDestroy
    void shutdown() {
        if (refreshTask != null) refreshTask.cancel(false);
        scheduler.shutdownNow();
    }

    /* ====================== Public API ====================== */

    /**
     * Step 1 of device flow — ask GitHub for a {@code user_code}. Returns the
     * code, verification URL, and polling parameters. The caller (e.g.
     * {@code /login github}) presents these to the user and then schedules
     * {@link #pollForOAuthToken(DeviceAuthSession)} to run in the background.
     */
    public DeviceAuthSession startDeviceFlow() {
        String body = "client_id=" + urlEncode(clientId) + "&scope=" + urlEncode(scope);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deviceCodeUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Map<String, Object> json = sendForJson(request, "device flow start");
        String deviceCode = stringField(json, "device_code");
        String userCode = stringField(json, "user_code");
        String verificationUri = stringField(json, "verification_uri");
        long expiresIn = longField(json, "expires_in", 900);
        long interval = longField(json, "interval", 5);

        DeviceAuthSession session = new DeviceAuthSession(
                deviceCode, userCode, verificationUri, interval, Instant.now().plusSeconds(expiresIn));
        pendingSession.set(session);
        log.info("[copilot] Device flow started — user_code={}, verify_url={}", userCode, verificationUri);
        return session;
    }

    /**
     * Poll GitHub for the OAuth token. Runs synchronously on the caller thread.
     * On success persists {@code gho_xxx} to the credentials file, exchanges it for
     * a Copilot bearer, and publishes {@link CopilotTokenRefreshedEvent}.
     *
     * @return the GitHub login once exchange succeeds
     * @throws DeviceFlowException on user denial, slow_down loop exhaustion, or expiry
     */
    public String pollForOAuthToken(DeviceAuthSession session) {
        long intervalSec = session.intervalSeconds();
        while (Instant.now().isBefore(session.expiresAt())) {
            sleep(Duration.ofSeconds(intervalSec));

            String body = "client_id=" + urlEncode(clientId)
                    + "&device_code=" + urlEncode(session.deviceCode())
                    + "&grant_type=" + urlEncode(DEVICE_GRANT_TYPE);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(accessTokenUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            Map<String, Object> json = sendForJson(request, "device flow poll");
            String error = stringFieldOrNull(json, "error");
            if (error == null) {
                String oauthToken = stringField(json, "access_token");
                return completeAuth(oauthToken);
            }
            switch (error) {
                case "authorization_pending" -> {
                    /* keep polling */
                }
                case "slow_down" -> intervalSec += 5;
                case "expired_token" -> throw new DeviceFlowException(
                        "Device code expired before authorization completed. Run /login github again.");
                case "access_denied" -> throw new DeviceFlowException(
                        "Authorization was denied. Run /login github to retry.");
                default -> throw new DeviceFlowException(
                        "GitHub returned an unexpected error: " + error);
            }
        }
        throw new DeviceFlowException("Device code expired (15 min limit). Run /login github again.");
    }

    /**
     * Return a Copilot bearer token, refreshing if the cached one is missing or near
     * expiry. Returns {@link Optional#empty()} when the user has not authenticated yet.
     */
    public synchronized Optional<String> getCopilotToken() {
        CopilotToken cached = cachedToken.get();
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minus(REFRESH_HEADROOM))) {
            return Optional.of(cached.token());
        }
        Optional<String> oauth = credentialsStore.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN);
        if (oauth.isEmpty()) {
            return Optional.empty();
        }
        try {
            CopilotToken fresh = exchangeCopilotToken(oauth.get());
            cachedToken.set(fresh);
            scheduleNextRefresh(fresh);
            eventPublisher.publishEvent(new CopilotTokenRefreshedEvent(this, fresh.expiresAt()));
            return Optional.of(fresh.token());
        } catch (Exception e) {
            log.error("[copilot] Failed to refresh Copilot token: {}", e.getMessage());
            throw e;
        }
    }

    /** Current authentication status — used by {@code /login status}. */
    public Status currentStatus() {
        if (pendingSession.get() != null
                && credentialsStore.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN).isEmpty()) {
            return Status.PENDING_DEVICE_AUTH;
        }
        return credentialsStore.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN).isPresent()
                ? Status.AUTHENTICATED
                : Status.NOT_AUTHENTICATED;
    }

    /** GitHub login (username) once authenticated, otherwise empty. */
    public Optional<String> getGithubLogin() {
        return credentialsStore.get(CopilotCredentialsStore.GITHUB_LOGIN);
    }

    /** Drop all Copilot credentials — both on-disk and in-memory. */
    public synchronized void logout() {
        credentialsStore.removePrefix("github.");
        cachedToken.set(null);
        pendingSession.set(null);
        if (refreshTask != null) refreshTask.cancel(false);
        eventPublisher.publishEvent(new CopilotTokenRefreshedEvent(this, Instant.EPOCH));
        log.info("[copilot] Logged out — credentials wiped");
    }

    /* ====================== Internal ====================== */

    private String completeAuth(String oauthToken) {
        String login = fetchGithubLogin(oauthToken);
        credentialsStore.putAll(Map.of(
                CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, oauthToken,
                CopilotCredentialsStore.GITHUB_LOGIN, login != null ? login : "",
                CopilotCredentialsStore.GITHUB_SAVED_AT, Instant.now().toString()));
        pendingSession.set(null);

        CopilotToken fresh = exchangeCopilotToken(oauthToken);
        cachedToken.set(fresh);
        scheduleNextRefresh(fresh);
        eventPublisher.publishEvent(new CopilotTokenRefreshedEvent(this, fresh.expiresAt()));
        log.info("[copilot] Authenticated as {} — Copilot token valid until {}", login, fresh.expiresAt());
        return login != null ? login : "(unknown)";
    }

    private CopilotToken exchangeCopilotToken(String oauthToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(copilotTokenUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "token " + oauthToken)
                .header("Accept", "application/json")
                .header("User-Agent", "kukuvaia")
                .GET()
                .build();

        Map<String, Object> json = sendForJson(request, "copilot token exchange");
        String token = stringField(json, "token");
        long expiresAtEpoch = longField(json, "expires_at", Instant.now().plusSeconds(1500).getEpochSecond());
        return new CopilotToken(token, Instant.ofEpochSecond(expiresAtEpoch));
    }

    private String fetchGithubLogin(String oauthToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(githubUserUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "token " + oauthToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "kukuvaia")
                    .GET()
                    .build();
            Map<String, Object> json = sendForJson(request, "github user lookup");
            return stringFieldOrNull(json, "login");
        } catch (Exception e) {
            log.warn("[copilot] Could not resolve GitHub login: {}", e.getMessage());
            return null;
        }
    }

    private void scheduleNextRefresh(CopilotToken token) {
        if (refreshTask != null) refreshTask.cancel(false);
        Duration until = Duration.between(Instant.now(), token.expiresAt()).minus(REFRESH_HEADROOM);
        long delay = Math.max(60, until.getSeconds());
        refreshTask = scheduler.schedule(() -> {
            try {
                getCopilotToken();
            } catch (Exception e) {
                log.warn("[copilot] Scheduled refresh failed: {}", e.getMessage());
            }
        }, delay, TimeUnit.SECONDS);
        log.debug("[copilot] Next refresh in {}s", delay);
    }

    private Map<String, Object> sendForJson(HttpRequest request, String operation) {
        String host = request.uri().getHost();
        Throwable lastConnectFailure = null;

        for (int attempt = 1; attempt <= CONNECT_RETRY_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code == 401 || code == 403) {
                    throw new DeviceFlowException(
                            "%s — authentication rejected (HTTP %d). GitHub OAuth token likely revoked; run /login github again."
                                    .formatted(operation, code));
                }
                if (code < 200 || code >= 300) {
                    String body = response.body() == null ? "" : response.body();
                    String preview = body.length() > 200 ? body.substring(0, 200) + "…" : body;
                    throw new DeviceFlowException(
                            "%s failed: HTTP %d from %s — %s".formatted(operation, code, host, preview.strip()));
                }
                return objectMapper.readValue(response.body(), new TypeReference<>() {});
            } catch (DeviceFlowException e) {
                throw e;
            } catch (HttpConnectTimeoutException | ConnectException e) {
                lastConnectFailure = e;
                if (attempt < CONNECT_RETRY_ATTEMPTS) {
                    log.warn("[copilot] {} — connect failed to {} (attempt {}/{}): {} — retrying",
                            operation, host, attempt, CONNECT_RETRY_ATTEMPTS, e.getMessage());
                    sleepQuiet(RETRY_BACKOFF);
                    continue;
                }
            } catch (HttpTimeoutException e) {
                throw new DeviceFlowException(
                        "%s — read timeout after %ds talking to %s".formatted(operation, REQUEST_TIMEOUT.toSeconds(), host));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DeviceFlowException("%s interrupted".formatted(operation));
            } catch (Exception e) {
                throw new DeviceFlowException("%s failed: %s".formatted(operation, e.getMessage()));
            }
        }

        String hint = lastConnectFailure instanceof HttpConnectTimeoutException
                ? "TCP connect timed out — GitHub may be rotating a bad LB IP from DNS, or your network blocks " + host
                : "TCP connection refused/reset — check network and any HTTP_PROXY";
        throw new DeviceFlowException(
                "%s — cannot reach %s after %d attempts: %s".formatted(operation, host, CONNECT_RETRY_ATTEMPTS, hint));
    }

    private static void sleepQuiet(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceFlowException("Polling interrupted");
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String stringField(Map<String, Object> json, String key) {
        Object v = json.get(key);
        if (v == null) throw new DeviceFlowException("Missing field '%s' in response".formatted(key));
        return v.toString();
    }

    private static String stringFieldOrNull(Map<String, Object> json, String key) {
        Object v = json.get(key);
        return v == null ? null : v.toString();
    }

    private static long longField(Map<String, Object> json, String key, long defaultValue) {
        Object v = json.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return defaultValue;
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /* ====================== Types ====================== */

    public enum Status { NOT_AUTHENTICATED, PENDING_DEVICE_AUTH, AUTHENTICATED }

    public record DeviceAuthSession(
            String deviceCode,
            String userCode,
            String verificationUri,
            long intervalSeconds,
            Instant expiresAt) {}

    record CopilotToken(String token, Instant expiresAt) {}

    public static class DeviceFlowException extends RuntimeException {
        public DeviceFlowException(String message) { super(message); }
    }
}

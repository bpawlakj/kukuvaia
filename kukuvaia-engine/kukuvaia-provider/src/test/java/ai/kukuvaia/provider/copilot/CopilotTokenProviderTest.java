package ai.kukuvaia.provider.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CopilotTokenProvider — device flow, token exchange, logout")
@ExtendWith(MockitoExtension.class)
class CopilotTokenProviderTest {

    @TempDir Path tempDir;
    @Mock private HttpClient httpClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CopilotCredentialsStore credentialsStore;
    private CopilotTokenProvider tokenProvider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        credentialsStore = new CopilotCredentialsStore(objectMapper,
                tempDir.resolve("credentials.json").toString());
        tokenProvider = new CopilotTokenProvider(
                credentialsStore, objectMapper, eventPublisher,
                "Iv1.test", "read:user",
                "https://example.test/device/code",
                "https://example.test/oauth/token",
                "https://example.test/copilot/token",
                "https://example.test/user");
        tokenProvider.setHttpClient(httpClient);
    }

    /** Queue scripted HTTP responses in send-order. */
    private void scriptResponses(String... bodies) throws Exception {
        Deque<HttpResponse<String>> queue = new ArrayDeque<>();
        for (String body : bodies) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(body);
            queue.add(response);
        }
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpResponse<String> next = queue.poll();
                    if (next == null) {
                        throw new IllegalStateException("No scripted response left for request: "
                                + invocation.<HttpRequest>getArgument(0).uri());
                    }
                    return next;
                });
    }

    @Test
    @DisplayName("getCopilotToken — no GitHub OAuth token stored — returns empty")
    void getCopilotToken_notAuthenticated_returnsEmpty() {
        assertThat(tokenProvider.getCopilotToken()).isEmpty();
        assertThat(tokenProvider.currentStatus())
                .isEqualTo(CopilotTokenProvider.Status.NOT_AUTHENTICATED);
    }

    @Test
    @DisplayName("getCopilotToken — OAuth token present — exchanges for bearer and publishes event")
    void getCopilotToken_withOauth_exchangesAndPublishes() throws Exception {
        credentialsStore.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc");
        long expiresAt = Instant.now().plusSeconds(1500).getEpochSecond();
        scriptResponses("""
                {"token": "tok_bearer_1", "expires_at": %d}
                """.formatted(expiresAt));

        Optional<String> bearer = tokenProvider.getCopilotToken();

        assertThat(bearer).contains("tok_bearer_1");
        verify(eventPublisher, atLeastOnce()).publishEvent(any(CopilotTokenRefreshedEvent.class));
    }

    @Test
    @DisplayName("getCopilotToken — cached bearer still valid — skips network")
    void getCopilotToken_cachedBearer_skipsNetwork() throws Exception {
        credentialsStore.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc");
        long expiresAt = Instant.now().plusSeconds(1500).getEpochSecond();
        scriptResponses("""
                {"token": "tok_first", "expires_at": %d}
                """.formatted(expiresAt));

        tokenProvider.getCopilotToken();
        Optional<String> second = tokenProvider.getCopilotToken();

        assertThat(second).contains("tok_first");
        // Cached bearer is still valid — second call must not hit the network.
        verify(httpClient, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("startDeviceFlow — returns user code + verification URL from GitHub response")
    void startDeviceFlow_returnsCodeAndUrl() throws Exception {
        scriptResponses("""
                {
                  "device_code": "dev_xyz",
                  "user_code": "ABCD-1234",
                  "verification_uri": "https://github.com/login/device",
                  "expires_in": 900,
                  "interval": 5
                }
                """);

        CopilotTokenProvider.DeviceAuthSession session = tokenProvider.startDeviceFlow();

        assertThat(session.userCode()).isEqualTo("ABCD-1234");
        assertThat(session.verificationUri()).isEqualTo("https://github.com/login/device");
        assertThat(session.deviceCode()).isEqualTo("dev_xyz");
        assertThat(tokenProvider.currentStatus())
                .isEqualTo(CopilotTokenProvider.Status.PENDING_DEVICE_AUTH);
    }

    @Test
    @DisplayName("pollForOAuthToken — access_denied — throws with retry hint")
    void pollForOAuthToken_accessDenied_throws() throws Exception {
        scriptResponses("{\"error\": \"access_denied\"}");
        CopilotTokenProvider.DeviceAuthSession session = new CopilotTokenProvider.DeviceAuthSession(
                "dev_xyz", "ABCD-1234", "https://example/device", 0, Instant.now().plusSeconds(900));

        assertThatThrownBy(() -> tokenProvider.pollForOAuthToken(session))
                .isInstanceOf(CopilotTokenProvider.DeviceFlowException.class)
                .hasMessageContaining("denied");
    }

    @Test
    @DisplayName("pollForOAuthToken — success — stores OAuth token and exchanges for bearer")
    void pollForOAuthToken_success_storesAndExchanges() throws Exception {
        long expiresAt = Instant.now().plusSeconds(1500).getEpochSecond();
        scriptResponses(
                """
                {"access_token": "gho_real_token"}
                """,
                """
                {"login": "octocat"}
                """,
                """
                {"token": "tok_bearer_after_login", "expires_at": %d}
                """.formatted(expiresAt));
        CopilotTokenProvider.DeviceAuthSession session = new CopilotTokenProvider.DeviceAuthSession(
                "dev_xyz", "ABCD-1234", "https://example/device", 0, Instant.now().plusSeconds(900));

        String login = tokenProvider.pollForOAuthToken(session);

        assertThat(login).isEqualTo("octocat");
        assertThat(credentialsStore.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN))
                .contains("gho_real_token");
        assertThat(credentialsStore.get(CopilotCredentialsStore.GITHUB_LOGIN))
                .contains("octocat");
        assertThat(tokenProvider.currentStatus())
                .isEqualTo(CopilotTokenProvider.Status.AUTHENTICATED);
    }

    @Test
    @DisplayName("getCopilotToken — connect timeout once then success — retries and returns bearer")
    void getCopilotToken_connectTimeoutThenSuccess_retries() throws Exception {
        credentialsStore.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc");
        long expiresAt = Instant.now().plusSeconds(1500).getEpochSecond();

        @SuppressWarnings("unchecked")
        HttpResponse<String> successResponse = org.mockito.Mockito.mock(HttpResponse.class);
        when(successResponse.statusCode()).thenReturn(200);
        when(successResponse.body()).thenReturn("""
                {"token": "tok_after_retry", "expires_at": %d}
                """.formatted(expiresAt));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpConnectTimeoutException("connect timed out"))
                .thenReturn(successResponse);

        Optional<String> bearer = tokenProvider.getCopilotToken();

        assertThat(bearer).contains("tok_after_retry");
    }

    @Test
    @DisplayName("getCopilotToken — 401 from copilot endpoint — fails with auth-rejected hint, no retry")
    void getCopilotToken_401_failsWithAuthHint() throws Exception {
        credentialsStore.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_revoked");

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> tokenProvider.getCopilotToken())
                .isInstanceOf(CopilotTokenProvider.DeviceFlowException.class)
                .hasMessageContaining("authentication rejected")
                .hasMessageContaining("/login github");
    }

    @Test
    @DisplayName("logout — wipes credentials, clears cache, publishes event")
    void logout_wipesEverything() {
        credentialsStore.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc");
        credentialsStore.put(CopilotCredentialsStore.GITHUB_LOGIN, "octocat");

        tokenProvider.logout();

        assertThat(credentialsStore.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN)).isEmpty();
        assertThat(credentialsStore.get(CopilotCredentialsStore.GITHUB_LOGIN)).isEmpty();
        assertThat(tokenProvider.currentStatus())
                .isEqualTo(CopilotTokenProvider.Status.NOT_AUTHENTICATED);

        ArgumentCaptor<CopilotTokenRefreshedEvent> captor =
                ArgumentCaptor.forClass(CopilotTokenRefreshedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(Instant.EPOCH);
    }
}

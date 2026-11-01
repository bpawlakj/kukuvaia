package ai.kukuvaia.provider.transport.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.time.Duration;
import java.util.Map;

/**
 * Builds the {@code openai-java} SDK clients behind Spring AI 2.x {@code OpenAiChatModel}.
 *
 * <p>Spring AI's own setup only accepts a single request timeout, and the SDK applies the
 * per-request {@link Timeout} on every call (overriding anything set on the HTTP client), so
 * a separate connect timeout can only be honoured by building {@link ClientOptions} here.
 * Mirrors Spring AI's {@code OpenAiSetup}, including its no-auth mode for local providers.
 *
 * @param baseUrl        SDK base URL (the SDK appends {@code /chat/completions})
 * @param apiKey         bearer key; empty means no-auth (the Authorization header is stripped)
 * @param connectTimeout TCP connect timeout
 * @param requestTimeout whole-request timeout
 * @param maxRetries     retries after the first attempt (0 = single attempt)
 * @param headers        provider-defined headers sent with every request
 */
public record OpenAiSdkClients(String baseUrl, String apiKey, Duration connectTimeout,
                               Duration requestTimeout, int maxRetries, Map<String, String> headers) {

    /** The SDK rejects an empty key; the interceptor below removes the header it produces. */
    private static final String NO_AUTH_PLACEHOLDER_KEY = "kukuvaia-no-auth";
    private static final String USER_AGENT = "kukuvaia-engine";

    public OpenAiSdkClients {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public OpenAIClient sync() {
        return new OpenAIClientImpl(clientOptions());
    }

    public OpenAIClientAsync async() {
        return new OpenAIClientAsyncImpl(clientOptions());
    }

    boolean noAuth() {
        return apiKey == null || apiKey.isBlank();
    }

    Timeout timeout() {
        return Timeout.builder()
                .connect(connectTimeout)
                .read(requestTimeout)
                .request(requestTimeout)
                .build();
    }

    private ClientOptions clientOptions() {
        Timeout timeout = timeout();
        SpringAiOpenAiHttpClient.Builder http = SpringAiOpenAiHttpClient.builder().timeout(timeout);
        if (noAuth()) {
            http.interceptor(chain -> chain.proceed(
                    chain.request().newBuilder().removeHeader("Authorization").build()));
        }
        ClientOptions.Builder options = ClientOptions.builder()
                .httpClient(http.build())
                .baseUrl(baseUrl)
                .apiKey(noAuth() ? NO_AUTH_PLACEHOLDER_KEY : apiKey)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .putHeader("User-Agent", USER_AGENT);
        headers.forEach(options::putHeader);
        return options.build();
    }
}

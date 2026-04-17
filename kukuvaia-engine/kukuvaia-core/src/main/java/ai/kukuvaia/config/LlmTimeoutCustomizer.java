package ai.kukuvaia.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Injects {@code request_timeout} into LLM API request bodies for litellm proxies.
 *
 * SmartGate uses litellm which has a default internal timeout of 10s.
 * When {@code request_timeout} is present in the request body, litellm uses it
 * instead of its default. Without this, LLM calls timeout on complex prompts
 * with tool use (which can take 30-60s).
 */
@Configuration
public class LlmTimeoutCustomizer {

    private static final Logger log = LoggerFactory.getLogger(LlmTimeoutCustomizer.class);

    @Bean
    RestClientCustomizer llmTimeoutRestClientCustomizer(
            @Value("${kukuvaia.llm.request-timeout:120}") int timeoutSeconds) {
        log.info("Configuring LLM request_timeout={}s for litellm-compatible proxies", timeoutSeconds);
        return builder -> builder.requestInterceptor(new LitellmTimeoutInterceptor(timeoutSeconds));
    }

    /**
     * Intercepts POST chat/completions requests and injects request_timeout into the body.
     * Note: SmartGate blocks custom headers (403), so only body injection is used.
     */
    static class LitellmTimeoutInterceptor implements ClientHttpRequestInterceptor {

        private final int timeoutSeconds;

        LitellmTimeoutInterceptor(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                             ClientHttpRequestExecution execution) throws IOException {
            String path = request.getURI().getPath();
            if ("POST".equals(request.getMethod().name()) && path.contains("chat/completions")) {
                body = injectTimeout(body);
            }
            return execution.execute(request, body);
        }

        private byte[] injectTimeout(byte[] body) {
            String json = new String(body);
            if (!json.contains("\"request_timeout\"") && json.startsWith("{")) {
                json = "{\"request_timeout\":" + timeoutSeconds + "," + json.substring(1);
            }
            return json.getBytes();
        }
    }
}

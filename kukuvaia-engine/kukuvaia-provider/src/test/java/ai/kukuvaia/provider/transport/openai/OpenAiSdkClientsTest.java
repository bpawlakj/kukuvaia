package ai.kukuvaia.provider.transport.openai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenAiSdkClients — SDK client options")
class OpenAiSdkClientsTest {

    @Test
    @DisplayName("connect and request timeouts are both carried into the SDK Timeout")
    void timeout_carriesConnectAndRequest() {
        var clients = new OpenAiSdkClients("http://localhost/v1", "k",
                Duration.ofSeconds(7), Duration.ofSeconds(90), 1, Map.of());

        assertThat(clients.timeout().connect()).isEqualTo(Duration.ofSeconds(7));
        assertThat(clients.timeout().request()).isEqualTo(Duration.ofSeconds(90));
        assertThat(clients.timeout().read()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("blank key means no-auth; negative retries are rejected")
    void noAuthAndValidation() {
        assertThat(new OpenAiSdkClients("http://x/v1", "", Duration.ofSeconds(1), Duration.ofSeconds(1), 0, null).noAuth()).isTrue();
        assertThat(new OpenAiSdkClients("http://x/v1", "k", Duration.ofSeconds(1), Duration.ofSeconds(1), 0, null).noAuth()).isFalse();
        assertThatThrownBy(() -> new OpenAiSdkClients("http://x/v1", "k", Duration.ofSeconds(1), Duration.ofSeconds(1), -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

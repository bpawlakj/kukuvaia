package ai.kukuvaia.provider.copilot;

import ai.kukuvaia.provider.secret.EnvVarSecretResolver;
import ai.kukuvaia.provider.secret.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("CopilotSecretResolver — composite routing")
@ExtendWith(MockitoExtension.class)
class CopilotSecretResolverTest {

    @Mock private ObjectProvider<CopilotTokenProvider> tokenProviderHolder;
    @Mock private CopilotTokenProvider tokenProvider;
    @Mock private EnvVarSecretResolver envResolver;

    private CopilotSecretResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new CopilotSecretResolver(tokenProviderHolder, envResolver);
    }

    @Test
    @DisplayName("resolve — copilot:* ref — returns live token from CopilotTokenProvider")
    void resolve_copilotRef_returnsLiveToken() {
        when(tokenProviderHolder.getObject()).thenReturn(tokenProvider);
        when(tokenProvider.getCopilotToken()).thenReturn(Optional.of("tok_live_xyz"));

        String result = resolver.resolve("copilot:default");

        assertThat(result).isEqualTo("tok_live_xyz");
    }

    @Test
    @DisplayName("resolve — copilot:* ref but not authenticated — throws with /login hint")
    void resolve_copilotRefUnauthenticated_throws() {
        when(tokenProviderHolder.getObject()).thenReturn(tokenProvider);
        when(tokenProvider.getCopilotToken()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("copilot:default"))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class)
                .hasMessageContaining("/login github");
    }

    @Test
    @DisplayName("resolve — env-var ref — delegates to EnvVarSecretResolver")
    void resolve_envVarRef_delegatesToEnvResolver() {
        when(envResolver.resolve("LLM_API_KEY")).thenReturn("env-secret-value");

        String result = resolver.resolve("LLM_API_KEY");

        assertThat(result).isEqualTo("env-secret-value");
    }

    @Test
    @DisplayName("resolve — null or blank ref — throws without touching token provider")
    void resolve_blankRef_throws() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class);
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class);
    }
}

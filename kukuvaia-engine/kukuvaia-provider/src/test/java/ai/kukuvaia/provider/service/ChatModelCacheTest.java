package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.config.LlmProvidersProperties;
import ai.kukuvaia.provider.secret.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("ChatModelCache — config-based caching and role routing")
@ExtendWith(MockitoExtension.class)
class ChatModelCacheTest {

    @Mock private ChatModelFactory chatModelFactory;
    @Mock private SecretResolver secretResolver;
    @Mock private ChatModel mockChatModel;

    private ChatModelCache cache;

    private LlmProvidersProperties buildProps(String modelId, String role) {
        LlmProvidersProperties props = new LlmProvidersProperties();

        LlmProvidersProperties.ProviderDef pd = new LlmProvidersProperties.ProviderDef();
        pd.setName("test-provider");
        pd.setType("anthropic");
        pd.setBaseUrl("https://api.anthropic.com");
        pd.setApiKeyRef("literal-test-key");
        pd.setEnabled(true);
        pd.setConfig(Map.of("auth-header", "x-api-key"));

        LlmProvidersProperties.ModelDef md = new LlmProvidersProperties.ModelDef();
        md.setModelId(modelId);
        md.setDisplayName("Test Model");
        md.setTier("standard");
        md.setMaxTokens(4096);
        pd.setModels(List.of(md));

        props.setProviders(List.of(pd));
        props.setRoles(Map.of(role, modelId));
        return props;
    }

    @BeforeEach
    void setUp() {
        lenient().when(secretResolver.resolve(any())).thenReturn("resolved-key");
        lenient().when(chatModelFactory.create(any(), any())).thenReturn(mockChatModel);
    }

    @Test
    @DisplayName("warmUp — builds config index and populates role index")
    void warmUp_populatesRoleIndex() {
        cache = new ChatModelCache(chatModelFactory, buildProps("claude-sonnet-4-6", "supervisor"), secretResolver);
        cache.warmUp();

        assertThat(cache.roleCount()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("getByRole — returns model for configured role")
    void getByRole_returnsModel() {
        cache = new ChatModelCache(chatModelFactory, buildProps("claude-sonnet-4-6", "supervisor"), secretResolver);
        cache.warmUp();

        ChatModel result = cache.getByRole("supervisor");
        assertThat(result).isSameAs(mockChatModel);
    }

    @Test
    @DisplayName("getByRole — unknown role returns null")
    void getByRole_unknownRole_returnsNull() {
        cache = new ChatModelCache(chatModelFactory, buildProps("claude-sonnet-4-6", "supervisor"), secretResolver);
        cache.warmUp();

        assertThat(cache.getByRole("advisor")).isNull();
    }

    @Test
    @DisplayName("getByModelId — cached after first access")
    void getByModelId_cachedOnSecondAccess() {
        cache = new ChatModelCache(chatModelFactory, buildProps("model-a", "supervisor"), secretResolver);
        cache.warmUp();

        var modelId = cache.getModelIdForRole("supervisor").orElseThrow();
        cache.getByModelId(modelId);
        cache.getByModelId(modelId);

        // Factory called only once during warmUp (cache hit on second getByModelId)
        verify(chatModelFactory, times(1)).create(any(), any());
    }

    @Test
    @DisplayName("getByModelId — unknown UUID throws")
    void getByModelId_unknownUuid_throws() {
        cache = new ChatModelCache(chatModelFactory, buildProps("model-a", "supervisor"), secretResolver);
        cache.warmUp();

        var unknownUuid = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> cache.getByModelId(unknownUuid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No config entry");
    }

    @Test
    @DisplayName("getModelIdForRole — empty when role not configured")
    void getModelIdForRole_returnsEmpty_whenMissing() {
        cache = new ChatModelCache(chatModelFactory, buildProps("model-a", "supervisor"), secretResolver);
        cache.warmUp();

        assertThat(cache.getModelIdForRole("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("invalidateModel — evicts from cache (no-op on static config)")
    void invalidateModel_evictsCache() {
        cache = new ChatModelCache(chatModelFactory, buildProps("model-a", "supervisor"), secretResolver);
        cache.warmUp();

        int beforeSize = cache.size();
        var modelId = cache.getModelIdForRole("supervisor").orElseThrow();
        cache.invalidateModel(modelId);

        assertThat(cache.size()).isLessThan(beforeSize);
    }

    @Test
    @DisplayName("warmUp — missing ANTHROPIC_API_KEY logs error and skips provider")
    void warmUp_secretResolutionFails_skipsProvider() {
        when(secretResolver.resolve(any()))
                .thenThrow(new SecretResolver.SecretNotFoundException("ANTHROPIC_API_KEY"));

        cache = new ChatModelCache(chatModelFactory, buildProps("model-a", "supervisor"), secretResolver);
        cache.warmUp();

        assertThat(cache.roleCount()).isEqualTo(0);
    }
}

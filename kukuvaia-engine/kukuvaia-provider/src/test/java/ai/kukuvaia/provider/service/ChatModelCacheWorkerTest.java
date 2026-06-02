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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ChatModelCache — getWorkerModels multi-worker support")
@ExtendWith(MockitoExtension.class)
class ChatModelCacheWorkerTest {

    @Mock private ChatModelFactory chatModelFactory;
    @Mock private SecretResolver secretResolver;

    @BeforeEach
    void setUp() {
        lenient().when(secretResolver.resolve(any())).thenReturn("resolved-key");
    }

    private LlmProvidersProperties buildMultiWorkerProps() {
        LlmProvidersProperties props = new LlmProvidersProperties();

        LlmProvidersProperties.ProviderDef pd = new LlmProvidersProperties.ProviderDef();
        pd.setName("test"); pd.setType("anthropic");
        pd.setBaseUrl("https://api.test.com"); pd.setApiKeyRef("TEST_KEY");
        pd.setEnabled(true);

        LlmProvidersProperties.ModelDef m1 = new LlmProvidersProperties.ModelDef();
        m1.setModelId("haiku"); m1.setMaxTokens(4096);

        LlmProvidersProperties.ModelDef m2 = new LlmProvidersProperties.ModelDef();
        m2.setModelId("sonnet"); m2.setMaxTokens(8192);

        pd.setModels(List.of(m1, m2));
        props.setProviders(List.of(pd));
        props.setRoles(Map.of("worker", "haiku", "worker-2", "sonnet", "supervisor", "sonnet"));
        return props;
    }

    @Test
    @DisplayName("getWorkerModels — returns models for worker and worker-N roles")
    void getWorkerModels_returnsAllWorkers() {
        ChatModel haikuModel = mock(ChatModel.class);
        ChatModel sonnetModel = mock(ChatModel.class);

        when(chatModelFactory.create(any(), any())).thenAnswer(inv -> {
            ai.kukuvaia.provider.model.ModelRecord model = inv.getArgument(1);
            return model.modelId().equals("haiku") ? haikuModel : sonnetModel;
        });

        var cache = new ChatModelCache(chatModelFactory, buildMultiWorkerProps(), secretResolver);
        cache.warmUp();

        List<ChatModel> workers = cache.getWorkerModels();
        assertThat(workers).hasSize(2).contains(haikuModel, sonnetModel);
    }

    @Test
    @DisplayName("getWorkerModels — excludes non-worker roles (e.g. supervisor)")
    void getWorkerModels_excludesSupervisor() {
        when(chatModelFactory.create(any(), any())).thenReturn(mock(ChatModel.class));

        var cache = new ChatModelCache(chatModelFactory, buildMultiWorkerProps(), secretResolver);
        cache.warmUp();

        // 3 roles: supervisor + worker + worker-2; only 2 are "worker*"
        assertThat(cache.getWorkerModels()).hasSize(2);
    }

    @Test
    @DisplayName("getWorkerModels — empty when no worker role configured")
    void getWorkerModels_empty_whenNoWorkerRole() {
        LlmProvidersProperties props = new LlmProvidersProperties();
        LlmProvidersProperties.ProviderDef pd = new LlmProvidersProperties.ProviderDef();
        pd.setName("p"); pd.setType("anthropic");
        pd.setBaseUrl("http://x"); pd.setApiKeyRef("KEY"); pd.setEnabled(true);
        LlmProvidersProperties.ModelDef md = new LlmProvidersProperties.ModelDef();
        md.setModelId("m"); md.setMaxTokens(4096);
        pd.setModels(List.of(md));
        props.setProviders(List.of(pd));
        props.setRoles(Map.of("supervisor", "m")); // no worker role

        when(chatModelFactory.create(any(), any())).thenReturn(mock(ChatModel.class));
        var cache = new ChatModelCache(chatModelFactory, props, secretResolver);
        cache.warmUp();

        assertThat(cache.getWorkerModels()).isEmpty();
    }
}

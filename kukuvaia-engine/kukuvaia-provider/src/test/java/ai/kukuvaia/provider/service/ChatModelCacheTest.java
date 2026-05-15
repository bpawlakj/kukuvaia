package ai.kukuvaia.provider.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.repository.ModelRepository;
import ai.kukuvaia.provider.model.ModelRoleRecord;
import ai.kukuvaia.provider.repository.ModelRoleRepository;
import ai.kukuvaia.provider.model.ProviderRecord;
import ai.kukuvaia.provider.repository.ProviderRepository;

@DisplayName("ChatModelCache — caching with lazy creation and invalidation")
@ExtendWith(MockitoExtension.class)
class ChatModelCacheTest {

    @Mock private ChatModelFactory chatModelFactory;
    @Mock private ProviderRepository providerRepository;
    @Mock private ModelRepository modelRepository;
    @Mock private ModelRoleRepository modelRoleRepository;
    @Mock private ChatModel mockChatModel;

    private ChatModelCache cache;

    private final UUID providerId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();

    private ProviderRecord testProvider() {
        return new ProviderRecord(providerId, "smartgate", "smartgate",
                "https://llm.example.com", "SMARTGATE_KEY",
                true, 0, Map.of(), Instant.now(), Instant.now());
    }

    private ModelRecord testModel() {
        return new ModelRecord(modelId, providerId, "haiku", "Claude Haiku",
                List.of("text"), "economy", 4096, 200000,
                true, Map.of(), null, Instant.now(), Instant.now());
    }

    @BeforeEach
    void setUp() {
        cache = new ChatModelCache(chatModelFactory, providerRepository, modelRepository, modelRoleRepository);
    }

    @Test
    @DisplayName("getByModelId cold — creates via factory, caches")
    void getByModelId_cold_createsAndCaches() {
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(testModel()));
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(testProvider()));
        when(chatModelFactory.create(any(), any())).thenReturn(mockChatModel);

        ChatModel result1 = cache.getByModelId(modelId);
        ChatModel result2 = cache.getByModelId(modelId);

        assertThat(result1).isSameAs(mockChatModel);
        assertThat(result2).isSameAs(result1);
        // Factory called only once (cached on second access)
        verify(chatModelFactory, times(1)).create(any(), any());
    }

    @Test
    @DisplayName("getByModelId — non-existent model — throws")
    void getByModelId_nonExistent_throws() {
        when(modelRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cache.getByModelId(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Model not found");
    }

    @Test
    @DisplayName("getByRole — role mapped to model — returns ChatModel")
    void getByRole_mapped_returnsChatModel() {
        // Setup role index
        when(modelRoleRepository.findAll()).thenReturn(List.of(
                new ModelRoleRecord(UUID.randomUUID(), "worker", modelId, null, Instant.now(), Instant.now())
        ));
        cache.refreshRoles();

        when(modelRepository.findById(modelId)).thenReturn(Optional.of(testModel()));
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(testProvider()));
        when(chatModelFactory.create(any(), any())).thenReturn(mockChatModel);

        ChatModel result = cache.getByRole("worker");

        assertThat(result).isSameAs(mockChatModel);
    }

    @Test
    @DisplayName("getByRole — unmapped role — returns null")
    void getByRole_unmapped_returnsNull() {
        ChatModel result = cache.getByRole("fantasy");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("invalidateModel — removes from cache, next access recreates")
    void invalidateModel_removesFromCache() {
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(testModel()));
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(testProvider()));
        when(chatModelFactory.create(any(), any())).thenReturn(mockChatModel);

        cache.getByModelId(modelId); // populate cache
        assertThat(cache.size()).isEqualTo(1);

        cache.invalidateModel(modelId);
        assertThat(cache.size()).isEqualTo(0);

        // Next access recreates
        cache.getByModelId(modelId);
        verify(chatModelFactory, times(2)).create(any(), any());
    }

    @Test
    @DisplayName("invalidateModel — non-cached — no-op")
    void invalidateModel_nonCached_noOp() {
        cache.invalidateModel(UUID.randomUUID());
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("invalidateProvider — evicts all models for provider")
    void invalidateProvider_evictsAll() {
        UUID model2Id = UUID.randomUUID();
        var model2 = new ModelRecord(model2Id, providerId, "sonnet", "Sonnet",
                List.of(), "standard", 4096, null, true, Map.of(), null, Instant.now(), Instant.now());

        when(modelRepository.findById(modelId)).thenReturn(Optional.of(testModel()));
        when(modelRepository.findById(model2Id)).thenReturn(Optional.of(model2));
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(testProvider()));
        when(chatModelFactory.create(any(), any())).thenReturn(mockChatModel);
        when(modelRepository.findByProviderId(providerId)).thenReturn(List.of(testModel(), model2));

        cache.getByModelId(modelId);
        cache.getByModelId(model2Id);
        assertThat(cache.size()).isEqualTo(2);

        cache.invalidateProvider(providerId);
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("refreshRoles — reloads from DB")
    void refreshRoles_reloadsFromDb() {
        when(modelRoleRepository.findAll()).thenReturn(List.of(
                new ModelRoleRecord(UUID.randomUUID(), "supervisor", modelId, null, Instant.now(), Instant.now()),
                new ModelRoleRecord(UUID.randomUUID(), "advisor", UUID.randomUUID(), null, Instant.now(), Instant.now())
        ));

        cache.refreshRoles();

        assertThat(cache.roleCount()).isEqualTo(2);
        assertThat(cache.getModelIdForRole("supervisor")).isPresent().contains(modelId);
        assertThat(cache.getModelIdForRole("fantasy")).isEmpty();
    }

    @Test
    @DisplayName("warmUp with no roles — no error")
    void warmUp_noRoles_noError() {
        when(modelRoleRepository.findAll()).thenReturn(List.of());

        cache.warmUp();

        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.roleCount()).isEqualTo(0);
    }
}

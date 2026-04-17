package ai.kukuvaia.provider.registry;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("ChatModelCache — getWorkerModels round-robin support")
@ExtendWith(MockitoExtension.class)
class ChatModelCacheWorkerTest {

    @Mock private ChatModelFactory chatModelFactory;
    @Mock private ProviderRepository providerRepository;
    @Mock private ModelRepository modelRepository;
    @Mock private ModelRoleRepository modelRoleRepository;
    @Mock private ChatModel mockModel;

    private ChatModelCache cache;

    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cache = new ChatModelCache(chatModelFactory, providerRepository, modelRepository, modelRoleRepository);
    }

    private ProviderRecord testProvider() {
        return new ProviderRecord(providerId, "test", "custom",
                "https://api.test.com", "TEST_KEY",
                true, 0, Map.of(), Instant.now(), Instant.now());
    }

    private ModelRecord model(UUID id, String name) {
        return new ModelRecord(id, providerId, name, name,
                List.of("text"), "economy", 4096, 200000,
                true, Map.of(), null, Instant.now(), Instant.now());
    }

    private ModelRoleRecord role(String roleName, UUID modelId) {
        return new ModelRoleRecord(UUID.randomUUID(), roleName, modelId, null, Instant.now(), Instant.now());
    }

    private void stubResolveAnyModel() {
        when(modelRepository.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(model(id, "model-" + id.toString().substring(0, 4)));
        });
        when(providerRepository.findById(any())).thenReturn(Optional.of(testProvider()));
        when(chatModelFactory.create(any(ProviderRecord.class), any(ModelRecord.class))).thenReturn(mockModel);
    }

    @Test
    @DisplayName("getWorkerModels — returns all worker-* roles")
    void getWorkerModels_returnsAllWorkerRoles() {
        UUID wId = UUID.randomUUID();
        UUID w2Id = UUID.randomUUID();
        UUID sId = UUID.randomUUID();

        when(modelRoleRepository.findAll()).thenReturn(List.of(
                role("worker", wId), role("worker-2", w2Id), role("supervisor", sId)
        ));
        cache.refreshRoles();
        stubResolveAnyModel();

        List<ChatModel> workers = cache.getWorkerModels();

        // worker + worker-2, NOT supervisor
        assertThat(workers).hasSize(2);
    }

    @Test
    @DisplayName("getWorkerModels — single worker returns single-element list")
    void getWorkerModels_singleWorker_returnsSingle() {
        UUID wId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();

        when(modelRoleRepository.findAll()).thenReturn(List.of(
                role("worker", wId), role("advisor", aId)
        ));
        cache.refreshRoles();
        stubResolveAnyModel();

        List<ChatModel> workers = cache.getWorkerModels();

        assertThat(workers).hasSize(1);
    }

    @Test
    @DisplayName("getWorkerModels — no workers returns empty list")
    void getWorkerModels_noWorkers_returnsEmpty() {
        UUID sId = UUID.randomUUID();

        when(modelRoleRepository.findAll()).thenReturn(List.of(
                role("supervisor", sId)
        ));
        cache.refreshRoles();

        List<ChatModel> workers = cache.getWorkerModels();

        assertThat(workers).isEmpty();
    }

    @Test
    @DisplayName("getWorkerModels — worker-3 pattern included")
    void getWorkerModels_worker3_included() {
        UUID w1 = UUID.randomUUID();
        UUID w2 = UUID.randomUUID();
        UUID w3 = UUID.randomUUID();

        when(modelRoleRepository.findAll()).thenReturn(List.of(
                role("worker", w1), role("worker-2", w2), role("worker-3", w3)
        ));
        cache.refreshRoles();
        stubResolveAnyModel();

        List<ChatModel> workers = cache.getWorkerModels();

        assertThat(workers).hasSize(3);
    }
}

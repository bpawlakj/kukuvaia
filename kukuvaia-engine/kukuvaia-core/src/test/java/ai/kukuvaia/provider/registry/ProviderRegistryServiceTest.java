package ai.kukuvaia.provider.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("ProviderRegistryService — business logic orchestration")
@ExtendWith(MockitoExtension.class)
class ProviderRegistryServiceTest {

    @Mock private ProviderRepository providerRepository;
    @Mock private ModelRepository modelRepository;
    @Mock private ModelRoleRepository modelRoleRepository;
    @Mock private ModelDiscoveryClient discoveryClient;
    @Mock private SecretResolver secretResolver;
    @Mock private ChatModelCache chatModelCache;

    private ProviderRegistryService service;

    @BeforeEach
    void setUp() {
        service = new ProviderRegistryService(providerRepository, modelRepository,
                modelRoleRepository, discoveryClient, secretResolver, chatModelCache);
    }

    private ProviderRecord testProvider(UUID id) {
        return new ProviderRecord(id, "smartgate", "smartgate",
                "https://llm.example.com", "SMARTGATE_KEY",
                true, 0, Map.of(), Instant.now(), Instant.now());
    }

    private ModelRecord testModel(UUID id, UUID providerId) {
        return new ModelRecord(id, providerId, "haiku", "Claude Haiku",
                List.of("text"), "economy", 4096, 200000,
                true, Map.of(), null, Instant.now(), Instant.now());
    }

    private ModelRoleRecord testRole(String role, UUID modelId) {
        return new ModelRoleRecord(UUID.randomUUID(), role, modelId,
                null, Instant.now(), Instant.now());
    }

    @Nested
    @DisplayName("deleteProvider")
    class DeleteProvider {

        @Test
        @DisplayName("no models — deletes successfully")
        void noModels_deletesSuccessfully() {
            UUID providerId = UUID.randomUUID();
            when(modelRepository.findByProviderId(providerId)).thenReturn(List.of());
            when(providerRepository.delete(providerId)).thenReturn(1);

            boolean result = service.deleteProvider(providerId);

            assertThat(result).isTrue();
            verify(providerRepository).delete(providerId);
        }

        @Test
        @DisplayName("model with active role — throws ProviderHasActiveRolesException")
        void modelWithActiveRole_throws() {
            UUID providerId = UUID.randomUUID();
            UUID modelId = UUID.randomUUID();
            var model = testModel(modelId, providerId);
            var role = testRole("advisor", modelId);

            when(modelRepository.findByProviderId(providerId)).thenReturn(List.of(model));
            when(modelRoleRepository.findByModelId(modelId)).thenReturn(List.of(role));

            assertThatThrownBy(() -> service.deleteProvider(providerId))
                    .isInstanceOf(ProviderRegistryService.ProviderHasActiveRolesException.class)
                    .hasMessageContaining("advisor");

            verify(providerRepository, never()).delete(any());
        }

        @Test
        @DisplayName("model without roles — deletes successfully")
        void modelWithoutRoles_deletesSuccessfully() {
            UUID providerId = UUID.randomUUID();
            UUID modelId = UUID.randomUUID();
            var model = testModel(modelId, providerId);

            when(modelRepository.findByProviderId(providerId)).thenReturn(List.of(model));
            when(modelRoleRepository.findByModelId(modelId)).thenReturn(List.of());
            when(providerRepository.delete(providerId)).thenReturn(1);

            boolean result = service.deleteProvider(providerId);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("non-existent provider — returns false")
        void nonExistent_returnsFalse() {
            UUID providerId = UUID.randomUUID();
            when(modelRepository.findByProviderId(providerId)).thenReturn(List.of());
            when(providerRepository.delete(providerId)).thenReturn(0);

            boolean result = service.deleteProvider(providerId);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("deleteModel")
    class DeleteModel {

        @Test
        @DisplayName("no active roles — deletes successfully")
        void noActiveRoles_deletesSuccessfully() {
            UUID modelId = UUID.randomUUID();
            when(modelRoleRepository.findByModelId(modelId)).thenReturn(List.of());
            when(modelRepository.delete(modelId)).thenReturn(1);

            boolean result = service.deleteModel(modelId);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("active role exists — throws ModelHasActiveRolesException")
        void activeRoleExists_throws() {
            UUID modelId = UUID.randomUUID();
            var role = testRole("worker", modelId);
            when(modelRoleRepository.findByModelId(modelId)).thenReturn(List.of(role));

            assertThatThrownBy(() -> service.deleteModel(modelId))
                    .isInstanceOf(ProviderRegistryService.ModelHasActiveRolesException.class)
                    .hasMessageContaining("worker");

            verify(modelRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("assignRole")
    class AssignRole {

        @Test
        @DisplayName("valid model — assigns successfully")
        void validModel_assignsSuccessfully() {
            UUID modelId = UUID.randomUUID();
            var model = testModel(modelId, UUID.randomUUID());
            var role = testRole("advisor", modelId);

            when(modelRepository.findById(modelId)).thenReturn(Optional.of(model));
            when(modelRoleRepository.upsert("advisor", modelId, "Strategic advisor")).thenReturn(role);

            ModelRoleRecord result = service.assignRole("advisor", modelId, "Strategic advisor");

            assertThat(result.role()).isEqualTo("advisor");
            assertThat(result.modelId()).isEqualTo(modelId);
        }

        @Test
        @DisplayName("non-existent model — throws IllegalArgumentException")
        void nonExistentModel_throws() {
            UUID modelId = UUID.randomUUID();
            when(modelRepository.findById(modelId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assignRole("advisor", modelId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Model not found");
        }
    }

    @Nested
    @DisplayName("createModel")
    class CreateModel {

        @Test
        @DisplayName("non-existent provider — throws IllegalArgumentException")
        void nonExistentProvider_throws() {
            UUID providerId = UUID.randomUUID();
            when(providerRepository.findById(providerId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createModel(
                    providerId, "haiku", "Haiku", List.of(), "economy", 4096, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Provider not found");
        }
    }

    @Nested
    @DisplayName("listProviders")
    class ListProviders {

        @Test
        @DisplayName("empty DB — returns empty list")
        void emptyDb_returnsEmptyList() {
            when(providerRepository.findAll()).thenReturn(List.of());

            List<ProviderResponse> result = service.listProviders();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("with providers — returns responses with model counts")
        void withProviders_returnsWithModelCounts() {
            UUID providerId = UUID.randomUUID();
            var provider = testProvider(providerId);
            when(providerRepository.findAll()).thenReturn(List.of(provider));
            when(modelRepository.countByProviderId(providerId)).thenReturn(3L);

            List<ProviderResponse> result = service.listProviders();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().modelCount()).isEqualTo(3);
            assertThat(result.getFirst().name()).isEqualTo("smartgate");
        }
    }

    @Nested
    @DisplayName("bulkAssignRoles")
    class BulkAssignRoles {

        @Test
        @DisplayName("multiple assignments — all processed")
        void multipleAssignments_allProcessed() {
            UUID model1 = UUID.randomUUID();
            UUID model2 = UUID.randomUUID();
            UUID providerId = UUID.randomUUID();

            when(modelRepository.findById(model1)).thenReturn(Optional.of(testModel(model1, providerId)));
            when(modelRepository.findById(model2)).thenReturn(Optional.of(testModel(model2, providerId)));
            when(modelRoleRepository.upsert(eq("worker"), eq(model1), any())).thenReturn(testRole("worker", model1));
            when(modelRoleRepository.upsert(eq("advisor"), eq(model2), any())).thenReturn(testRole("advisor", model2));
            when(modelRoleRepository.findByRole("worker")).thenReturn(Optional.of(testRole("worker", model1)));
            when(modelRoleRepository.findByRole("advisor")).thenReturn(Optional.of(testRole("advisor", model2)));
            when(providerRepository.findById(providerId)).thenReturn(Optional.of(testProvider(providerId)));

            var assignments = List.of(
                    new ModelRoleAssignment("worker", model1, null),
                    new ModelRoleAssignment("advisor", model2, null)
            );

            List<ModelRoleResponse> result = service.bulkAssignRoles(assignments);

            assertThat(result).hasSize(2);
        }
    }
}

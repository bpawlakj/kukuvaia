package ai.kukuvaia.dream;

import ai.kukuvaia.provider.registry.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("DreamService — health check and config audit")
@ExtendWith(MockitoExtension.class)
class DreamServiceTest {

    @Mock private DreamReportRepository reportRepository;
    @Mock private DreamRecommendationRepository recommendationRepository;
    @Mock private ProviderRepository providerRepository;
    @Mock private ModelRepository modelRepository;
    @Mock private ModelRoleRepository modelRoleRepository;
    @Mock private SecretResolver secretResolver;

    private DreamService service;

    @BeforeEach
    void setUp() {
        service = new DreamService(reportRepository, recommendationRepository,
                providerRepository, modelRepository, modelRoleRepository, secretResolver);
    }

    private ProviderRecord provider(UUID id, String name) {
        return new ProviderRecord(id, name, "smartgate", "https://llm.example.com",
                "API_KEY", true, 0, Map.of(), Instant.now(), Instant.now());
    }

    private ModelRecord model(UUID id, UUID providerId, boolean enabled) {
        return new ModelRecord(id, providerId, "sonnet", "Sonnet", List.of(), "standard",
                4096, null, enabled, Map.of(), null, Instant.now(), Instant.now());
    }

    private ModelRoleRecord role(String roleName, UUID modelId) {
        return new ModelRoleRecord(UUID.randomUUID(), roleName, modelId, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("healthCheck — all healthy → no recommendations")
    void healthCheck_allHealthy_noRecommendations() {
        UUID providerId = UUID.randomUUID();
        when(providerRepository.findAll()).thenReturn(List.of(provider(providerId, "smartgate")));
        when(secretResolver.resolve("API_KEY")).thenReturn("valid-key");
        when(modelRepository.countByProviderId(providerId)).thenReturn(3L);

        var snapshot = new LinkedHashMap<String, Object>();
        var recommendations = new ArrayList<DreamService.DreamRecommendation>();

        service.healthCheck(snapshot, recommendations);

        assertThat(recommendations).isEmpty();
        assertThat(snapshot).containsKey("providerCount");
    }

    @Test
    @DisplayName("healthCheck — missing API key → recommendation")
    void healthCheck_missingKey_recommendation() {
        UUID providerId = UUID.randomUUID();
        when(providerRepository.findAll()).thenReturn(List.of(provider(providerId, "smartgate")));
        when(secretResolver.resolve("API_KEY")).thenThrow(new SecretResolver.SecretNotFoundException("API_KEY"));
        when(modelRepository.countByProviderId(providerId)).thenReturn(1L);

        var snapshot = new LinkedHashMap<String, Object>();
        var recommendations = new ArrayList<DreamService.DreamRecommendation>();

        service.healthCheck(snapshot, recommendations);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.getFirst().type()).isEqualTo("CONFIG_INCONSISTENCY");
        assertThat(recommendations.getFirst().priority()).isEqualTo("high");
    }

    @Test
    @DisplayName("healthCheck — provider with 0 models → recommendation")
    void healthCheck_noModels_recommendation() {
        UUID providerId = UUID.randomUUID();
        when(providerRepository.findAll()).thenReturn(List.of(provider(providerId, "empty")));
        when(secretResolver.resolve("API_KEY")).thenReturn("valid-key");
        when(modelRepository.countByProviderId(providerId)).thenReturn(0L);

        var snapshot = new LinkedHashMap<String, Object>();
        var recommendations = new ArrayList<DreamService.DreamRecommendation>();

        service.healthCheck(snapshot, recommendations);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.getFirst().description()).contains("no models");
    }

    @Test
    @DisplayName("configAudit — all roles valid → no recommendations")
    void configAudit_allValid_noRecommendations() {
        UUID modelId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(modelRoleRepository.findAll()).thenReturn(List.of(
                role("supervisor", modelId), role("worker", modelId), role("advisor", modelId)
        ));
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(model(modelId, providerId, true)));

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        assertThat(recommendations).isEmpty();
    }

    @Test
    @DisplayName("configAudit — role points to disabled model → recommendation")
    void configAudit_disabledModel_recommendation() {
        UUID modelId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(modelRoleRepository.findAll()).thenReturn(List.of(
                role("supervisor", modelId), role("worker", modelId), role("advisor", modelId)
        ));
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(model(modelId, providerId, false)));

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.getFirst().description()).contains("disabled");
    }

    @Test
    @DisplayName("configAudit — missing critical role → recommendation")
    void configAudit_missingCriticalRole_recommendation() {
        when(modelRoleRepository.findAll()).thenReturn(List.of()); // no roles at all

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        // Should recommend supervisor, worker, advisor
        assertThat(recommendations).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("configAudit — role points to non-existent model → critical recommendation")
    void configAudit_nonExistentModel_critical() {
        UUID missingModelId = UUID.randomUUID();
        when(modelRoleRepository.findAll()).thenReturn(List.of(
                role("supervisor", missingModelId), role("worker", missingModelId), role("advisor", missingModelId)
        ));
        when(modelRepository.findById(missingModelId)).thenReturn(Optional.empty());

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.getFirst().priority()).isEqualTo("critical");
    }
}

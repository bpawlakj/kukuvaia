package ai.kukuvaia.dream;

import ai.kukuvaia.provider.config.LlmProvidersProperties;
import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.secret.SecretResolver;
import ai.kukuvaia.provider.service.ChatModelCache;
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
    @Mock private ChatModelCache chatModelCache;
    @Mock private SecretResolver secretResolver;

    private DreamService service;

    @BeforeEach
    void setUp() {
        // Default providers with 1 provider, 1 model, valid API key
    }

    private LlmProvidersProperties buildProps(List<String> roles, boolean withModels, String apiKeyRef) {
        LlmProvidersProperties props = new LlmProvidersProperties();

        LlmProvidersProperties.ProviderDef pd = new LlmProvidersProperties.ProviderDef();
        pd.setName("test-provider");
        pd.setType("anthropic");
        pd.setBaseUrl("https://api.example.com");
        pd.setApiKeyRef(apiKeyRef);
        pd.setEnabled(true);

        if (withModels) {
            LlmProvidersProperties.ModelDef md = new LlmProvidersProperties.ModelDef();
            md.setModelId("sonnet");
            md.setMaxTokens(4096);
            pd.setModels(List.of(md));
        } else {
            pd.setModels(List.of());
        }
        props.setProviders(List.of(pd));

        Map<String, String> roleMap = new HashMap<>();
        for (String role : roles) roleMap.put(role, "sonnet");
        props.setRoles(roleMap);
        return props;
    }

    private ModelRecord modelRecord(boolean enabled) {
        return new ModelRecord(UUID.randomUUID(), UUID.randomUUID(), "sonnet", "Sonnet",
                List.of(), "standard", 4096, null, enabled, Map.of(), null,
                Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    @DisplayName("healthCheck — all healthy → no recommendations")
    void healthCheck_allHealthy_noRecommendations() {
        var props = buildProps(List.of("supervisor", "worker", "advisor"), true, "VALID_KEY");
        service = new DreamService(reportRepository, recommendationRepository, props, chatModelCache, secretResolver);
        when(secretResolver.resolve("VALID_KEY")).thenReturn("actual-key");

        var snapshot = new LinkedHashMap<String, Object>();
        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.healthCheck(snapshot, recommendations);

        assertThat(recommendations).isEmpty();
        assertThat(snapshot).containsKey("providerCount");
    }

    @Test
    @DisplayName("healthCheck — missing API key → recommendation")
    void healthCheck_missingKey_recommendation() {
        var props = buildProps(List.of(), true, "MISSING_KEY");
        service = new DreamService(reportRepository, recommendationRepository, props, chatModelCache, secretResolver);
        when(secretResolver.resolve("MISSING_KEY")).thenThrow(new SecretResolver.SecretNotFoundException("MISSING_KEY"));

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
        var props = buildProps(List.of(), false, "VALID_KEY");
        service = new DreamService(reportRepository, recommendationRepository, props, chatModelCache, secretResolver);
        when(secretResolver.resolve("VALID_KEY")).thenReturn("actual-key");

        var snapshot = new LinkedHashMap<String, Object>();
        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.healthCheck(snapshot, recommendations);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.getFirst().description()).contains("no models");
    }

    @Test
    @DisplayName("configAudit — all roles valid → no recommendations")
    void configAudit_allValid_noRecommendations() {
        var props = buildProps(List.of("supervisor", "worker", "advisor"), true, "KEY");
        service = new DreamService(reportRepository, recommendationRepository, props, chatModelCache, secretResolver);
        when(chatModelCache.getModelRecord("sonnet")).thenReturn(Optional.of(modelRecord(true)));

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        assertThat(recommendations).isEmpty();
    }

    @Test
    @DisplayName("configAudit — role points to disabled model → recommendation")
    void configAudit_disabledModel_recommendation() {
        var props = buildProps(List.of("supervisor", "worker", "advisor"), true, "KEY");
        service = new DreamService(reportRepository, recommendationRepository, props, chatModelCache, secretResolver);
        when(chatModelCache.getModelRecord("sonnet")).thenReturn(Optional.of(modelRecord(false)));

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.getFirst().description()).contains("disabled");
    }

    @Test
    @DisplayName("configAudit — missing critical role → recommendation")
    void configAudit_missingCriticalRole_recommendation() {
        var props = buildProps(List.of(), true, "KEY");  // no roles
        service = new DreamService(reportRepository, recommendationRepository, props, chatModelCache, secretResolver);

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        assertThat(recommendations).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("configAudit — role references unknown modelId → critical recommendation")
    void configAudit_unknownModelId_critical() {
        var props = buildProps(List.of("supervisor", "worker", "advisor"), true, "KEY");
        service = new DreamService(reportRepository, recommendationRepository, props, chatModelCache, secretResolver);
        when(chatModelCache.getModelRecord("sonnet")).thenReturn(Optional.empty());

        var recommendations = new ArrayList<DreamService.DreamRecommendation>();
        service.configAudit(recommendations);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.getFirst().priority()).isEqualTo("critical");
    }
}

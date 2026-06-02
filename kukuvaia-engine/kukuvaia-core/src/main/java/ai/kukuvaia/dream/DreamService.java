package ai.kukuvaia.dream;

import ai.kukuvaia.provider.config.LlmProvidersProperties;
import ai.kukuvaia.provider.secret.SecretResolver;
import ai.kukuvaia.provider.service.ChatModelCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orchestrates dream runs: deterministic health checks, config audits,
 * and produces reports with recommendations.
 *
 * Phase A: deterministic tasks (no LLM) — health check + config audit.
 * Config is read from {@link LlmProvidersProperties} (file-based) instead of the DB.
 */
@Service
public class DreamService {

    private static final Logger log = LoggerFactory.getLogger(DreamService.class);

    private final DreamReportRepository reportRepository;
    private final DreamRecommendationRepository recommendationRepository;
    private final LlmProvidersProperties providersProperties;
    private final ChatModelCache chatModelCache;
    private final SecretResolver secretResolver;

    public DreamService(DreamReportRepository reportRepository,
                        DreamRecommendationRepository recommendationRepository,
                        LlmProvidersProperties providersProperties,
                        ChatModelCache chatModelCache,
                        SecretResolver secretResolver) {
        this.reportRepository = reportRepository;
        this.recommendationRepository = recommendationRepository;
        this.providersProperties = providersProperties;
        this.chatModelCache = chatModelCache;
        this.secretResolver = secretResolver;
    }

    public UUID runDream() {
        DreamReportRecord report = reportRepository.create();
        UUID reportId = report.id();
        log.info("Dream run started: {}", reportId);

        try {
            var snapshot = new LinkedHashMap<String, Object>();
            var recommendations = new ArrayList<DreamRecommendation>();

            healthCheck(snapshot, recommendations);
            configAudit(recommendations);

            snapshot.put("recommendationCount", recommendations.size());
            reportRepository.complete(reportId,
                    "Dream run: %d recommendations".formatted(recommendations.size()),
                    0, List.of(), snapshot);

            for (var rec : recommendations) {
                recommendationRepository.save(reportId,
                        rec.type(), rec.priority(), rec.description(),
                        rec.suggestedAction(), rec.confidence(), rec.evidence());
            }
            log.info("Dream run completed: {} recommendations", recommendations.size());
        } catch (Exception e) {
            log.error("Dream run failed: {}", e.getMessage(), e);
            reportRepository.fail(reportId, e.getMessage());
        }

        return reportId;
    }

    void healthCheck(Map<String, Object> snapshot, List<DreamRecommendation> recommendations) {
        var providers = providersProperties.getProviders();
        int providerCount = providers != null ? providers.size() : 0;
        snapshot.put("providerCount", providerCount);

        if (providers == null || providers.isEmpty()) {
            recommendations.add(new DreamRecommendation(
                    "CONFIG_INCONSISTENCY", "critical",
                    "No providers configured",
                    "Add provider to kukuvaia.llm-providers in application.yaml",
                    0.99, Map.of()
            ));
            return;
        }

        for (var provider : providers) {
            var providerHealth = new LinkedHashMap<String, Object>();
            providerHealth.put("name", provider.getName());
            providerHealth.put("type", provider.getType());
            providerHealth.put("enabled", provider.isEnabled());

            try {
                secretResolver.resolve(provider.getApiKeyRef());
                providerHealth.put("apiKeyStatus", "ok");
            } catch (SecretResolver.SecretNotFoundException e) {
                providerHealth.put("apiKeyStatus", "missing");
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "high",
                        "API key '%s' not found for provider '%s'".formatted(provider.getApiKeyRef(), provider.getName()),
                        "Set environment variable or literal key in kukuvaia.llm-providers.providers[].apiKeyRef",
                        0.95, Map.of("provider", provider.getName())
                ));
            }

            int modelCount = provider.getModels() != null ? provider.getModels().size() : 0;
            providerHealth.put("modelCount", modelCount);
            if (modelCount == 0 && provider.isEnabled()) {
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "medium",
                        "Provider '%s' has no models configured".formatted(provider.getName()),
                        "Add models under kukuvaia.llm-providers.providers[].models[] in application.yaml",
                        0.8, Map.of("provider", provider.getName())
                ));
            }

            snapshot.put("provider:" + provider.getName(), providerHealth);
        }
    }

    void configAudit(List<DreamRecommendation> recommendations) {
        var roles = providersProperties.getRoles();
        var criticalRoles = Set.of("supervisor", "worker", "advisor");
        var assignedRoles = new HashSet<String>();

        if (roles != null) {
            for (var entry : roles.entrySet()) {
                String roleName = entry.getKey();
                String modelId = entry.getValue();
                assignedRoles.add(roleName);

                var model = chatModelCache.getModelRecord(modelId);
                if (model.isEmpty()) {
                    recommendations.add(new DreamRecommendation(
                            "CONFIG_INCONSISTENCY", "critical",
                            "Role '%s' references unknown modelId '%s'".formatted(roleName, modelId),
                            "Fix kukuvaia.llm-providers.roles.%s in application.yaml".formatted(roleName),
                            0.99, Map.of("role", roleName, "modelId", modelId)
                    ));
                } else if (!model.get().enabled()) {
                    recommendations.add(new DreamRecommendation(
                            "CONFIG_INCONSISTENCY", "high",
                            "Role '%s' points to disabled model '%s'".formatted(roleName, model.get().modelId()),
                            "Re-enable model in application.yaml or reassign role",
                            0.9, Map.of("role", roleName, "model", model.get().modelId())
                    ));
                }
            }
        }

        for (String critical : criticalRoles) {
            if (!assignedRoles.contains(critical)) {
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "medium",
                        "Critical role '%s' is not assigned to any model".formatted(critical),
                        "Add '%s' to kukuvaia.llm-providers.roles in application.yaml".formatted(critical),
                        0.85, Map.of("role", critical)
                ));
            }
        }
    }

    // --- Query methods ---

    public Optional<DreamReportRecord> getReport(UUID id) { return reportRepository.findById(id); }
    public Optional<DreamReportRecord> getLatestReport() { return reportRepository.findLatest(); }
    public List<DreamReportRecord> listReports(int limit) { return reportRepository.findAll(limit); }
    public List<DreamRecommendationRecord> getRecommendations(UUID reportId) { return recommendationRepository.findByReportId(reportId); }
    public List<DreamRecommendationRecord> getPendingRecommendations() { return recommendationRepository.findPending(); }
    public boolean acceptRecommendation(UUID id, String resolvedBy) { return recommendationRepository.accept(id, resolvedBy) > 0; }
    public boolean rejectRecommendation(UUID id, String resolvedBy, String reason) { return recommendationRepository.reject(id, resolvedBy, reason) > 0; }

    record DreamRecommendation(String type, String priority, String description,
                               String suggestedAction, double confidence, Map<String, Object> evidence) {}
}

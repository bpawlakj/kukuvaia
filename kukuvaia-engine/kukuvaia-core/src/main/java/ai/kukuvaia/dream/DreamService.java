package ai.kukuvaia.dream;

import ai.kukuvaia.provider.registry.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orchestrates dream runs: deterministic health checks, config audits,
 * and produces reports with recommendations.
 *
 * Phase A: deterministic tasks (no LLM) — health check + config audit.
 * Phase B+: LLM-powered tasks added in T14 (memory consolidation, model scout).
 */
@Service
public class DreamService {

    private static final Logger log = LoggerFactory.getLogger(DreamService.class);

    private final DreamReportRepository reportRepository;
    private final DreamRecommendationRepository recommendationRepository;
    private final ProviderRepository providerRepository;
    private final ModelRepository modelRepository;
    private final ModelRoleRepository modelRoleRepository;
    private final SecretResolver secretResolver;

    public DreamService(DreamReportRepository reportRepository,
                        DreamRecommendationRepository recommendationRepository,
                        ProviderRepository providerRepository,
                        ModelRepository modelRepository,
                        ModelRoleRepository modelRoleRepository,
                        SecretResolver secretResolver) {
        this.reportRepository = reportRepository;
        this.recommendationRepository = recommendationRepository;
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.modelRoleRepository = modelRoleRepository;
        this.secretResolver = secretResolver;
    }

    /**
     * Run a dream cycle: health check + config audit.
     * Returns the report ID.
     */
    public UUID runDream() {
        DreamReportRecord report = reportRepository.create();
        UUID reportId = report.id();
        log.info("Dream run started: {}", reportId);

        try {
            var healthSnapshot = new LinkedHashMap<String, Object>();
            var recommendations = new ArrayList<DreamRecommendation>();

            // Task 1: Health check (deterministic, no LLM)
            healthCheck(healthSnapshot, recommendations);

            // Task 2: Config audit (deterministic, no LLM)
            configAudit(recommendations);

            // Save recommendations
            for (var rec : recommendations) {
                recommendationRepository.save(reportId, rec.type, rec.priority,
                        rec.description, rec.suggestedAction, rec.confidence, rec.evidence);
            }

            String summary = "Dream completed: %d checks, %d recommendations".formatted(
                    healthSnapshot.size(), recommendations.size());

            reportRepository.complete(reportId, summary, 0, List.of(), healthSnapshot);
            log.info("Dream run completed: {} — {}", reportId, summary);

        } catch (Exception e) {
            log.error("Dream run failed: {}", reportId, e);
            reportRepository.fail(reportId, e.getMessage());
        }

        return reportId;
    }

    /**
     * Health check: verify providers, API keys, model availability.
     */
    void healthCheck(Map<String, Object> snapshot, List<DreamRecommendation> recommendations) {
        var providers = providerRepository.findAll();
        snapshot.put("providerCount", providers.size());

        for (var provider : providers) {
            var providerHealth = new LinkedHashMap<String, Object>();
            providerHealth.put("name", provider.name());
            providerHealth.put("type", provider.type());
            providerHealth.put("enabled", provider.enabled());

            // Check API key
            try {
                secretResolver.resolve(provider.apiKeyRef());
                providerHealth.put("apiKeyStatus", "ok");
            } catch (SecretResolver.SecretNotFoundException e) {
                providerHealth.put("apiKeyStatus", "missing");
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "high",
                        "API key '%s' not found for provider '%s'".formatted(provider.apiKeyRef(), provider.name()),
                        "Set environment variable: " + provider.apiKeyRef(),
                        0.95, Map.of("provider", provider.name())
                ));
            }

            // Check model count
            long modelCount = modelRepository.countByProviderId(provider.id());
            providerHealth.put("modelCount", modelCount);
            if (modelCount == 0 && provider.enabled()) {
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "medium",
                        "Provider '%s' has no models configured".formatted(provider.name()),
                        "POST /api/providers/%s/sync-models".formatted(provider.id()),
                        0.8, Map.of("provider", provider.name())
                ));
            }

            snapshot.put("provider:" + provider.name(), providerHealth);
        }
    }

    /**
     * Config audit: verify role assignments, orphaned roles, critical roles present.
     */
    void configAudit(List<DreamRecommendation> recommendations) {
        var roles = modelRoleRepository.findAll();
        var criticalRoles = Set.of("supervisor", "worker", "advisor");
        var assignedRoles = new HashSet<String>();

        for (var role : roles) {
            assignedRoles.add(role.role());

            // Check if model exists and is enabled
            var model = modelRepository.findById(role.modelId());
            if (model.isEmpty()) {
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "critical",
                        "Role '%s' points to non-existent model %s".formatted(role.role(), role.modelId()),
                        "DELETE /api/models/roles/" + role.role(),
                        0.99, Map.of("role", role.role())
                ));
            } else if (!model.get().enabled()) {
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "high",
                        "Role '%s' points to disabled model '%s'".formatted(role.role(), model.get().modelId()),
                        "Re-enable model or reassign role",
                        0.9, Map.of("role", role.role(), "model", model.get().modelId())
                ));
            }
        }

        // Check critical roles
        for (String critical : criticalRoles) {
            if (!assignedRoles.contains(critical)) {
                recommendations.add(new DreamRecommendation(
                        "CONFIG_INCONSISTENCY", "medium",
                        "Critical role '%s' is not assigned to any model".formatted(critical),
                        "POST /api/models/roles with role=%s".formatted(critical),
                        0.85, Map.of("role", critical)
                ));
            }
        }
    }

    // --- Query methods ---

    public Optional<DreamReportRecord> getReport(UUID id) {
        return reportRepository.findById(id);
    }

    public Optional<DreamReportRecord> getLatestReport() {
        return reportRepository.findLatest();
    }

    public List<DreamReportRecord> listReports(int limit) {
        return reportRepository.findAll(limit);
    }

    public List<DreamRecommendationRecord> getRecommendations(UUID reportId) {
        return recommendationRepository.findByReportId(reportId);
    }

    public List<DreamRecommendationRecord> getPendingRecommendations() {
        return recommendationRepository.findPending();
    }

    public boolean acceptRecommendation(UUID id, String resolvedBy) {
        return recommendationRepository.accept(id, resolvedBy) > 0;
    }

    public boolean rejectRecommendation(UUID id, String resolvedBy, String reason) {
        return recommendationRepository.reject(id, resolvedBy, reason) > 0;
    }

    record DreamRecommendation(String type, String priority, String description,
                               String suggestedAction, double confidence, Map<String, Object> evidence) {}
}

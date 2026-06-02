package ai.kukuvaia.api;

import ai.kukuvaia.dream.DreamRecommendationRecord;
import ai.kukuvaia.dream.DreamReportRecord;
import ai.kukuvaia.dream.DreamService;
import ai.kukuvaia.harness.HarnessService;
import ai.kukuvaia.provider.config.LlmProvidersProperties;
import ai.kukuvaia.provider.service.ChatModelCache;
import ai.kukuvaia.provider.service.ComplexityMappingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard endpoints. Provider / model / role data is now derived from
 * {@link LlmProvidersProperties} (file-based config) and the in-memory
 * {@link ChatModelCache} instead of the database.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final LlmProvidersProperties providersProperties;
    private final ComplexityMappingService complexityMappingService;
    private final DreamService dreamService;
    private final HarnessService harnessService;
    private final ChatModelCache chatModelCache;

    public DashboardController(LlmProvidersProperties providersProperties,
                               ComplexityMappingService complexityMappingService,
                               DreamService dreamService,
                               HarnessService harnessService,
                               ChatModelCache chatModelCache) {
        this.providersProperties = providersProperties;
        this.complexityMappingService = complexityMappingService;
        this.dreamService = dreamService;
        this.harnessService = harnessService;
        this.chatModelCache = chatModelCache;
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        var overview = new LinkedHashMap<String, Object>();

        var providers = providersProperties.getProviders();
        overview.put("providerCount", providers != null ? providers.size() : 0);
        if (providers != null) {
            overview.put("providers", providers.stream()
                    .filter(LlmProvidersProperties.ProviderDef::isEnabled)
                    .map(p -> Map.of(
                            "name", p.getName(),
                            "type", p.getType(),
                            "enabled", p.isEnabled(),
                            "modelCount", p.getModels() != null ? p.getModels().size() : 0))
                    .toList());
        }

        int totalModels = providers == null ? 0 : providers.stream()
                .filter(LlmProvidersProperties.ProviderDef::isEnabled)
                .mapToInt(p -> p.getModels() != null ? p.getModels().size() : 0)
                .sum();
        overview.put("modelCount", totalModels);

        var roles = providersProperties.getRoles();
        overview.put("roleCount", roles != null ? roles.size() : 0);
        if (roles != null) {
            overview.put("roles", roles.entrySet().stream()
                    .map(e -> Map.of("role", e.getKey(), "modelId", e.getValue()))
                    .toList());
        }

        overview.put("complexityMappings", complexityMappingService.listMappings().size());
        overview.put("cachedModels", chatModelCache.size());
        overview.put("cachedRoles", chatModelCache.roleCount());
        overview.put("ruleCount", harnessService.listAll().size());

        var pendingRecs = dreamService.getPendingRecommendations();
        overview.put("pendingRecommendations", pendingRecs.size());
        dreamService.getLatestReport().ifPresent(r ->
                overview.put("lastDreamRun", Map.of(
                        "id", r.id(), "status", r.status(),
                        "startedAt", r.startedAt().toString(),
                        "summary", r.summary() != null ? r.summary() : ""
                )));

        return ResponseEntity.ok(overview);
    }

    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> activity() {
        var activity = new LinkedHashMap<String, Object>();

        List<DreamReportRecord> recentDreams = dreamService.listReports(5);
        activity.put("recentDreamReports", recentDreams.stream()
                .map(r -> Map.of(
                        "id", r.id(), "status", r.status(),
                        "startedAt", r.startedAt().toString(),
                        "summary", r.summary() != null ? r.summary() : ""))
                .toList());

        List<DreamRecommendationRecord> pending = dreamService.getPendingRecommendations();
        activity.put("pendingRecommendations", pending.stream()
                .map(r -> Map.of(
                        "id", r.id(), "type", r.type(),
                        "priority", r.priority(), "description", r.description()))
                .toList());

        return ResponseEntity.ok(activity);
    }

    /**
     * Health check endpoint — used by onboarding-sensei smoke test and CI.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var health = new LinkedHashMap<String, Object>();

        health.put("status", "ok");

        var providers = providersProperties.getProviders();
        health.put("providers", providers != null ? (int) providers.stream()
                .filter(LlmProvidersProperties.ProviderDef::isEnabled).count() : 0);

        int totalModels = providers == null ? 0 : providers.stream()
                .filter(LlmProvidersProperties.ProviderDef::isEnabled)
                .mapToInt(p -> p.getModels() != null ? p.getModels().size() : 0).sum();
        health.put("models", totalModels);

        health.put("roles", chatModelCache.roleCount());
        health.put("cachedModels", chatModelCache.size());

        health.put("hasSupervisorRole", chatModelCache.getModelIdForRole("supervisor").isPresent());
        health.put("hasWorkerRole", chatModelCache.getModelIdForRole("worker").isPresent());
        health.put("hasAdvisorRole", chatModelCache.getModelIdForRole("advisor").isPresent());

        return ResponseEntity.ok(health);
    }
}

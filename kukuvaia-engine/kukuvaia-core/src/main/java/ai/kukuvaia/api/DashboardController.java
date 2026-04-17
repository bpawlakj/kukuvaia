package ai.kukuvaia.api;

import ai.kukuvaia.dream.DreamRecommendationRecord;
import ai.kukuvaia.dream.DreamReportRecord;
import ai.kukuvaia.dream.DreamService;
import ai.kukuvaia.harness.HarnessService;
import ai.kukuvaia.provider.registry.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard endpoints for admin UI. Aggregates system state across all subsystems.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProviderRegistryService providerRegistry;
    private final ModelRoleRepository modelRoleRepository;
    private final ComplexityMappingService complexityMappingService;
    private final DreamService dreamService;
    private final HarnessService harnessService;
    private final ChatModelCache chatModelCache;

    public DashboardController(ProviderRegistryService providerRegistry,
                               ModelRoleRepository modelRoleRepository,
                               ComplexityMappingService complexityMappingService,
                               DreamService dreamService,
                               HarnessService harnessService,
                               ChatModelCache chatModelCache) {
        this.providerRegistry = providerRegistry;
        this.modelRoleRepository = modelRoleRepository;
        this.complexityMappingService = complexityMappingService;
        this.dreamService = dreamService;
        this.harnessService = harnessService;
        this.chatModelCache = chatModelCache;
    }

    /**
     * System overview — providers, models, roles, agents, health snapshot.
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        var overview = new LinkedHashMap<String, Object>();

        // Providers
        var providers = providerRegistry.listProviders();
        overview.put("providerCount", providers.size());
        overview.put("providers", providers.stream()
                .map(p -> Map.of("name", p.name(), "type", p.type(),
                        "enabled", p.enabled(), "modelCount", p.modelCount()))
                .toList());

        // Models
        var models = providerRegistry.listModels();
        overview.put("modelCount", models.size());

        // Roles
        var roles = providerRegistry.listRoles();
        overview.put("roleCount", roles.size());
        overview.put("roles", roles.stream()
                .map(r -> Map.of("role", r.role(), "model", r.modelDisplayName(),
                        "tier", r.modelTier(), "provider", r.providerName()))
                .toList());

        // Complexity mappings
        overview.put("complexityMappings", complexityMappingService.listMappings().size());

        // Cache
        overview.put("cachedModels", chatModelCache.size());
        overview.put("cachedRoles", chatModelCache.roleCount());

        // Harness
        overview.put("groupCount", harnessService.listGroups().size());
        overview.put("ruleSetCount", harnessService.listRuleSets().size());

        // Dream
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

    /**
     * Recent activity — last dream reports, pending recommendations.
     */
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
     * System health — quick check of all subsystems.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var health = new LinkedHashMap<String, Object>();

        health.put("status", "ok");
        health.put("providers", providerRegistry.listProviders().size());
        health.put("models", providerRegistry.listModels().size());
        health.put("roles", providerRegistry.listRoles().size());
        health.put("cachedModels", chatModelCache.size());

        // Check critical roles
        var roles = providerRegistry.listRoles();
        var roleNames = roles.stream().map(r -> r.role()).toList();
        health.put("hasSupervisorRole", roleNames.contains("supervisor"));
        health.put("hasWorkerRole", roleNames.contains("worker"));
        health.put("hasAdvisorRole", roleNames.contains("advisor"));

        return ResponseEntity.ok(health);
    }
}

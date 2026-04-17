package ai.kukuvaia.api;

import ai.kukuvaia.provider.registry.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Model and role management endpoints.
 * Handles model CRUD and routing role assignments.
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ProviderRegistryService registryService;
    private final ComplexityMappingService complexityMappingService;

    public ModelController(ProviderRegistryService registryService,
                           ComplexityMappingService complexityMappingService) {
        this.registryService = registryService;
        this.complexityMappingService = complexityMappingService;
    }

    @PostMapping
    public ResponseEntity<ModelResponse> createModel(@RequestBody Map<String, Object> body) {
        UUID providerId = UUID.fromString((String) body.get("providerId"));
        String modelId = (String) body.get("modelId");
        if (modelId == null || modelId.isBlank()) return ResponseEntity.badRequest().build();

        @SuppressWarnings("unchecked")
        var capabilities = body.containsKey("capabilities") ? (List<String>) body.get("capabilities") : List.<String>of();
        String tier = (String) body.getOrDefault("tier", "standard");
        int maxTokens = body.containsKey("maxTokens") ? ((Number) body.get("maxTokens")).intValue() : 4096;
        Integer contextWindow = body.containsKey("contextWindow") ? ((Number) body.get("contextWindow")).intValue() : null;

        try {
            var model = registryService.createModel(providerId, modelId,
                    (String) body.get("displayName"), capabilities, tier, maxTokens, contextWindow);
            return ResponseEntity.status(HttpStatus.CREATED).body(ModelResponse.from(model, "unknown"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/test")
    public ResponseEntity<?> testModel(@RequestBody Map<String, String> body) {
        String providerIdStr = body.get("providerId");
        String modelId = body.get("modelId");
        if (providerIdStr == null || modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "providerId and modelId are required"));
        }
        try {
            UUID providerId = UUID.fromString(providerIdStr);
            ModelTestResult result = registryService.testModel(providerId, modelId);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "latencyMs", result.latencyMs(),
                    "response", result.response()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("status", "failed", "error", e.getMessage()));
        } catch (ModelDiscoveryClient.ModelDiscoveryException e) {
            return ResponseEntity.ok(Map.of("status", "failed", "error", e.getMessage()));
        } catch (SecretResolver.SecretNotFoundException e) {
            return ResponseEntity.ok(Map.of("status", "failed", "error", "API key not configured"));
        }
    }

    @GetMapping
    public ResponseEntity<List<ModelResponse>> listModels(
            @RequestParam(required = false) String tier) {
        if (tier != null) {
            return ResponseEntity.ok(registryService.listModelsByTier(tier));
        }
        return ResponseEntity.ok(registryService.listModels());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModelResponse> getModel(@PathVariable UUID id) {
        return registryService.getModel(id)
                .map(m -> ResponseEntity.ok(ModelResponse.from(m, "unknown")))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ModelResponse> updateModel(@PathVariable UUID id,
                                                     @RequestBody UpdateModelRequest request) {
        return registryService.updateModel(id, request)
                .map(m -> ResponseEntity.ok(ModelResponse.from(m, "unknown")))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable UUID id) {
        try {
            boolean deleted = registryService.deleteModel(id);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (ProviderRegistryService.ModelHasActiveRolesException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    // --- Role endpoints ---

    @GetMapping("/roles")
    public ResponseEntity<List<ModelRoleResponse>> listRoles() {
        return ResponseEntity.ok(registryService.listRoles());
    }

    @PostMapping("/roles")
    public ResponseEntity<List<ModelRoleResponse>> bulkAssignRoles(
            @RequestBody List<ModelRoleAssignment> assignments) {
        return ResponseEntity.ok(registryService.bulkAssignRoles(assignments));
    }

    @PutMapping("/roles/{role}")
    public ResponseEntity<ModelRoleResponse> assignRole(
            @PathVariable String role,
            @RequestBody Map<String, String> body) {
        String modelIdStr = body.get("modelId");
        if (modelIdStr == null || modelIdStr.isBlank()) return ResponseEntity.badRequest().build();
        try {
            UUID modelId = UUID.fromString(modelIdStr);
            String description = body.get("description");
            registryService.assignRole(role, modelId, description);
            return registryService.getRole(role)
                    .map(r -> {
                        var roles = registryService.listRoles();
                        return ResponseEntity.ok(roles.stream()
                                .filter(mr -> mr.role().equals(role))
                                .findFirst().orElseThrow());
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/roles/{role}")
    public ResponseEntity<Void> deleteRole(@PathVariable String role) {
        boolean deleted = registryService.deleteRole(role);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // --- Complexity mapping endpoints ---

    @GetMapping("/complexity-mappings")
    public ResponseEntity<List<ComplexityMappingRepository.ComplexityMapping>> listComplexityMappings() {
        return ResponseEntity.ok(complexityMappingService.listMappings());
    }

    @PutMapping("/complexity-mappings/{complexity}")
    public ResponseEntity<Void> updateComplexityMapping(
            @PathVariable String complexity,
            @RequestBody java.util.Map<String, String> body) {
        String role = body.get("role");
        if (role == null || role.isBlank()) return ResponseEntity.badRequest().build();
        boolean updated = complexityMappingService.updateMapping(complexity, role);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}

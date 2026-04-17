package ai.kukuvaia.api;

import ai.kukuvaia.provider.registry.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provider management endpoints.
 * Handles provider CRUD and model listing per provider.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderRegistryService registryService;

    public ProviderController(ProviderRegistryService registryService) {
        this.registryService = registryService;
    }

    @PostMapping
    public ResponseEntity<ProviderResponse> createProvider(@RequestBody CreateProviderRequest request) {
        ProviderRecord created = registryService.createProvider(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProviderResponse.from(created, 0));
    }

    @GetMapping
    public ResponseEntity<List<ProviderResponse>> listProviders() {
        return ResponseEntity.ok(registryService.listProviders());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProviderResponse> getProvider(@PathVariable UUID id) {
        return registryService.getProvider(id)
                .map(p -> ResponseEntity.ok(ProviderResponse.from(p, 0)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProviderResponse> updateProvider(@PathVariable UUID id,
                                                           @RequestBody UpdateProviderRequest request) {
        return registryService.updateProvider(id, request)
                .map(p -> ResponseEntity.ok(ProviderResponse.from(p, 0)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProvider(@PathVariable UUID id) {
        try {
            boolean deleted = registryService.deleteProvider(id);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (ProviderRegistryService.ProviderHasActiveRolesException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @GetMapping("/{id}/models")
    public ResponseEntity<List<ModelResponse>> listProviderModels(@PathVariable UUID id) {
        if (registryService.getProvider(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(registryService.listModelsByProvider(id));
    }

    @PostMapping("/{id}/sync-models")
    public ResponseEntity<?> syncModels(@PathVariable UUID id) {
        try {
            SyncModelsResponse result = registryService.syncModels(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (ModelDiscoveryClient.ModelDiscoveryException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        } catch (SecretResolver.SecretNotFoundException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "API key not configured: " + e.getMessage()));
        }
    }

    @PostMapping("/test-connection")
    public ResponseEntity<?> testProviderRaw(@RequestBody Map<String, String> body) {
        String baseUrl = body.get("baseUrl");
        String apiKeyRef = body.get("apiKeyRef");
        if (baseUrl == null || baseUrl.isBlank() || apiKeyRef == null || apiKeyRef.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseUrl and apiKeyRef are required"));
        }
        try {
            long latencyMs = registryService.testProviderRaw(baseUrl, apiKeyRef);
            return ResponseEntity.ok(Map.of("status", "connected", "latencyMs", latencyMs));
        } catch (ModelDiscoveryClient.ModelDiscoveryException e) {
            return ResponseEntity.ok(Map.of("status", "failed", "error", e.getMessage()));
        } catch (SecretResolver.SecretNotFoundException e) {
            return ResponseEntity.ok(Map.of("status", "failed", "error", "API key not configured: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testProvider(@PathVariable UUID id) {
        try {
            long latencyMs = registryService.testProvider(id);
            return ResponseEntity.ok(Map.of("status", "connected", "latencyMs", latencyMs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (ModelDiscoveryClient.ModelDiscoveryException e) {
            return ResponseEntity.ok(Map.of("status", "failed", "error", e.getMessage()));
        } catch (SecretResolver.SecretNotFoundException e) {
            return ResponseEntity.ok(Map.of("status", "failed", "error", "API key not configured"));
        }
    }
}

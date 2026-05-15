package ai.kukuvaia.provider.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ai.kukuvaia.provider.dto.CreateProviderRequest;
import ai.kukuvaia.provider.model.DiscoveredModel;
import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.repository.ModelRepository;
import ai.kukuvaia.provider.dto.ModelResponse;
import ai.kukuvaia.provider.model.ModelRoleAssignment;
import ai.kukuvaia.provider.model.ModelRoleRecord;
import ai.kukuvaia.provider.repository.ModelRoleRepository;
import ai.kukuvaia.provider.dto.ModelRoleResponse;
import ai.kukuvaia.provider.dto.ModelTestResult;
import ai.kukuvaia.provider.model.ProviderRecord;
import ai.kukuvaia.provider.repository.ProviderRepository;
import ai.kukuvaia.provider.dto.ProviderResponse;
import ai.kukuvaia.provider.secret.SecretResolver;
import ai.kukuvaia.provider.dto.SyncModelsResponse;
import ai.kukuvaia.provider.dto.UpdateModelRequest;
import ai.kukuvaia.provider.dto.UpdateProviderRequest;

/**
 * Orchestrates provider, model, and role CRUD operations.
 * Validates business rules (e.g., cannot delete model with active role).
 */
@Service
public class ProviderRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistryService.class);

    private final ProviderRepository providerRepository;
    private final ModelRepository modelRepository;
    private final ModelRoleRepository modelRoleRepository;
    private final ModelDiscoveryClient discoveryClient;
    private final SecretResolver secretResolver;
    private final ChatModelCache chatModelCache;

    public ProviderRegistryService(ProviderRepository providerRepository,
                                   ModelRepository modelRepository,
                                   ModelRoleRepository modelRoleRepository,
                                   ModelDiscoveryClient discoveryClient,
                                   SecretResolver secretResolver,
                                   ChatModelCache chatModelCache) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.modelRoleRepository = modelRoleRepository;
        this.discoveryClient = discoveryClient;
        this.secretResolver = secretResolver;
        this.chatModelCache = chatModelCache;
    }

    // --- Providers ---

    public ProviderRecord createProvider(CreateProviderRequest request) {
        return providerRepository.save(
                request.name(), request.type(), request.baseUrl(),
                request.apiKeyRef(), request.priority(), request.config());
    }

    public Optional<ProviderRecord> getProvider(UUID id) {
        return providerRepository.findById(id);
    }

    public List<ProviderResponse> listProviders() {
        return providerRepository.findAll().stream()
                .map(p -> ProviderResponse.from(p, modelRepository.countByProviderId(p.id())))
                .toList();
    }

    public Optional<ProviderRecord> updateProvider(UUID id, UpdateProviderRequest request) {
        int updated = providerRepository.update(id, request);
        if (updated == 0) return Optional.empty();
        chatModelCache.invalidateProvider(id);
        log.info("Invalidated cached ChatModels after provider update: {}", id);
        return providerRepository.findById(id);
    }

    public boolean deleteProvider(UUID id) {
        // Check if any model of this provider has an active role
        List<ModelRecord> models = modelRepository.findByProviderId(id);
        for (ModelRecord model : models) {
            List<ModelRoleRecord> roles = modelRoleRepository.findByModelId(model.id());
            if (!roles.isEmpty()) {
                throw new ProviderHasActiveRolesException(id,
                        roles.stream().map(ModelRoleRecord::role).toList());
            }
        }
        return providerRepository.delete(id) > 0;
    }

    // --- Models ---

    public ModelRecord createModel(UUID providerId, String modelId, String displayName,
                                   List<String> capabilities, String tier, int maxTokens,
                                   Integer contextWindow) {
        return createModel(providerId, modelId, displayName, capabilities, tier, maxTokens,
                contextWindow, Map.of());
    }

    public ModelRecord createModel(UUID providerId, String modelId, String displayName,
                                   List<String> capabilities, String tier, int maxTokens,
                                   Integer contextWindow, Map<String, Object> config) {
        if (providerRepository.findById(providerId).isEmpty()) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }
        return modelRepository.save(providerId, modelId, displayName, capabilities,
                tier, maxTokens, contextWindow, null, config);
    }

    public ModelRecord createDiscoveredModel(UUID providerId, String modelId, Instant discoveredAt) {
        return modelRepository.save(providerId, modelId, null, List.of(),
                "standard", 4096, null, discoveredAt);
    }

    public Optional<ModelRecord> getModel(UUID id) {
        return modelRepository.findById(id);
    }

    public List<ModelResponse> listModels() {
        return modelRepository.findAll().stream()
                .map(m -> {
                    String providerName = providerRepository.findById(m.providerId())
                            .map(ProviderRecord::name).orElse("unknown");
                    return ModelResponse.from(m, providerName);
                })
                .toList();
    }

    public List<ModelResponse> listModelsByProvider(UUID providerId) {
        return modelRepository.findByProviderId(providerId).stream()
                .map(m -> {
                    String providerName = providerRepository.findById(m.providerId())
                            .map(ProviderRecord::name).orElse("unknown");
                    return ModelResponse.from(m, providerName);
                })
                .toList();
    }

    public List<ModelResponse> listModelsByTier(String tier) {
        return modelRepository.findByTier(tier).stream()
                .map(m -> {
                    String providerName = providerRepository.findById(m.providerId())
                            .map(ProviderRecord::name).orElse("unknown");
                    return ModelResponse.from(m, providerName);
                })
                .toList();
    }

    public Optional<ModelRecord> updateModel(UUID id, UpdateModelRequest request) {
        int updated = modelRepository.update(id, request);
        if (updated == 0) return Optional.empty();
        chatModelCache.invalidateModel(id);
        log.info("Invalidated cached ChatModel after model update: {}", id);
        return modelRepository.findById(id);
    }

    public boolean deleteModel(UUID id) {
        // DB enforces ON DELETE RESTRICT for model_roles, but let's give a clear error
        List<ModelRoleRecord> roles = modelRoleRepository.findByModelId(id);
        if (!roles.isEmpty()) {
            throw new ModelHasActiveRolesException(id,
                    roles.stream().map(ModelRoleRecord::role).toList());
        }
        return modelRepository.delete(id) > 0;
    }

    // --- Roles ---

    public ModelRoleRecord assignRole(String role, UUID modelId, String description) {
        if (modelRepository.findById(modelId).isEmpty()) {
            throw new IllegalArgumentException("Model not found: " + modelId);
        }
        ModelRoleRecord result = modelRoleRepository.upsert(role, modelId, description);
        chatModelCache.refreshRoles();
        log.info("Refreshed role index after role assignment: {} → {}", role, modelId);
        return result;
    }

    public List<ModelRoleResponse> listRoles() {
        return modelRoleRepository.findAll().stream()
                .map(this::toRoleResponse)
                .toList();
    }

    public Optional<ModelRoleRecord> getRole(String role) {
        return modelRoleRepository.findByRole(role);
    }

    public boolean deleteRole(String role) {
        boolean deleted = modelRoleRepository.delete(role) > 0;
        if (deleted) chatModelCache.refreshRoles();
        return deleted;
    }

    public List<ModelRoleResponse> bulkAssignRoles(List<ModelRoleAssignment> assignments) {
        return assignments.stream()
                .map(a -> {
                    assignRole(a.role(), a.modelId(), a.description());
                    return toRoleResponse(modelRoleRepository.findByRole(a.role()).orElseThrow());
                })
                .toList();
    }

    private ModelRoleResponse toRoleResponse(ModelRoleRecord roleRecord) {
        ModelRecord model = modelRepository.findById(roleRecord.modelId()).orElse(null);
        String providerName = "unknown";
        String displayName = "unknown";
        String tier = "unknown";
        if (model != null) {
            displayName = model.displayName() != null ? model.displayName() : model.modelId();
            tier = model.tier();
            providerName = providerRepository.findById(model.providerId())
                    .map(ProviderRecord::name).orElse("unknown");
        }
        return new ModelRoleResponse(roleRecord.role(), roleRecord.modelId(), displayName, tier, providerName, roleRecord.description());
    }

    // --- Discovery & Sync ---

    /**
     * Discover models from provider's /v1/models endpoint and sync to DB.
     * New models are added. Existing models are unchanged. Missing models are soft-disabled.
     */
    public SyncModelsResponse syncModels(UUID providerId) {
        ProviderRecord provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        String apiKey = secretResolver.resolve(provider.apiKeyRef());
        List<DiscoveredModel> discovered = discoveryClient.discover(provider.baseUrl(), apiKey, provider.config());

        List<ModelRecord> existing = modelRepository.findByProviderId(providerId);
        var existingIds = existing.stream().map(ModelRecord::modelId).collect(java.util.stream.Collectors.toSet());
        var discoveredIds = discovered.stream().map(DiscoveredModel::modelId).collect(java.util.stream.Collectors.toSet());

        var syncedModels = new java.util.ArrayList<SyncModelsResponse.SyncedModel>();
        int added = 0, disabled = 0, unchanged = 0;

        // Add new models
        for (DiscoveredModel dm : discovered) {
            if (!existingIds.contains(dm.modelId())) {
                createDiscoveredModel(providerId, dm.modelId(), Instant.now());
                syncedModels.add(new SyncModelsResponse.SyncedModel(dm.modelId(), "added"));
                added++;
            } else {
                syncedModels.add(new SyncModelsResponse.SyncedModel(dm.modelId(), "unchanged"));
                unchanged++;
            }
        }

        // Soft-disable models that disappeared
        for (ModelRecord em : existing) {
            if (!discoveredIds.contains(em.modelId()) && em.enabled()) {
                modelRepository.update(em.id(), new UpdateModelRequest(null, null, null, null, null, false, null));
                syncedModels.add(new SyncModelsResponse.SyncedModel(em.modelId(), "disabled"));
                disabled++;
            }
        }

        log.info("Synced models for provider {}: discovered={}, added={}, disabled={}, unchanged={}",
                provider.name(), discovered.size(), added, disabled, unchanged);

        return new SyncModelsResponse(discovered.size(), added, disabled, unchanged, syncedModels);
    }

    /**
     * Test provider connectivity. Returns latency in ms.
     */
    public long testProvider(UUID providerId) {
        ProviderRecord provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        String apiKey = secretResolver.resolve(provider.apiKeyRef());
        return discoveryClient.testConnectivity(provider.baseUrl(), apiKey, provider.config());
    }

    /**
     * Test provider connectivity with raw connection details (before saving).
     * Returns latency in ms. Optional {@code providerConfig} carries the same
     * overrides that the persisted provider would use ({@code models-path},
     * {@code headers}) so the test mirrors production behaviour.
     */
    public long testProviderRaw(String baseUrl, String apiKeyRef) {
        return testProviderRaw(baseUrl, apiKeyRef, Map.of());
    }

    public long testProviderRaw(String baseUrl, String apiKeyRef, Map<String, Object> providerConfig) {
        String apiKey = secretResolver.resolve(apiKeyRef);
        return discoveryClient.testConnectivity(baseUrl, apiKey, providerConfig);
    }

    /**
     * Test a specific model by sending a minimal chat completion request.
     * When {@code config} contains {@code thinking=true}, the test includes the
     * {@code reasoning} request parameter so thinking models return both
     * reasoning and final content.
     */
    public ModelTestResult testModel(UUID providerId, String modelId, Map<String, Object> config) {
        ProviderRecord provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        String apiKey = secretResolver.resolve(provider.apiKeyRef());
        return discoveryClient.testModel(provider.baseUrl(), apiKey, modelId, provider.config(), config);
    }

    public ModelTestResult testModel(UUID providerId, String modelId) {
        return testModel(providerId, modelId, Map.of());
    }

    // --- Exceptions ---

    public static class ProviderHasActiveRolesException extends RuntimeException {
        public ProviderHasActiveRolesException(UUID providerId, List<String> roles) {
            super("Cannot delete provider %s: models have active roles: %s. Reassign roles first."
                    .formatted(providerId, roles));
        }
    }

    public static class ModelHasActiveRolesException extends RuntimeException {
        public ModelHasActiveRolesException(UUID modelId, List<String> roles) {
            super("Cannot delete model %s: has active roles: %s. Reassign roles first."
                    .formatted(modelId, roles));
        }
    }
}

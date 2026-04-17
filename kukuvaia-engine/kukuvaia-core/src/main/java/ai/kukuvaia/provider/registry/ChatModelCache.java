package ai.kukuvaia.provider.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for dynamically created {@link ChatModel} instances.
 * Lazy-creates models on first access via {@link ChatModelFactory}.
 * Supports invalidation per model, per provider, and role index refresh.
 */
@Component
public class ChatModelCache {

    private static final Logger log = LoggerFactory.getLogger(ChatModelCache.class);

    private final ConcurrentHashMap<UUID, ChatModel> modelCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> roleIndex = new ConcurrentHashMap<>();

    private final ChatModelFactory chatModelFactory;
    private final ProviderRepository providerRepository;
    private final ModelRepository modelRepository;
    private final ModelRoleRepository modelRoleRepository;

    public ChatModelCache(ChatModelFactory chatModelFactory,
                          ProviderRepository providerRepository,
                          ModelRepository modelRepository,
                          ModelRoleRepository modelRoleRepository) {
        this.chatModelFactory = chatModelFactory;
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.modelRoleRepository = modelRoleRepository;
    }

    /**
     * Get or create a ChatModel by model UUID. Lazy-creates on first access.
     */
    public ChatModel getByModelId(UUID modelId) {
        return modelCache.computeIfAbsent(modelId, this::createChatModel);
    }

    /**
     * Get a ChatModel by routing role (e.g., "supervisor", "advisor", "worker").
     *
     * @return the ChatModel for the role, or null if role not assigned
     */
    public ChatModel getByRole(String role) {
        UUID modelId = roleIndex.get(role);
        if (modelId == null) return null;
        return getByModelId(modelId);
    }

    /**
     * Get the model UUID assigned to a role.
     */
    public Optional<UUID> getModelIdForRole(String role) {
        return Optional.ofNullable(roleIndex.get(role));
    }

    /**
     * Get all ChatModels assigned to worker roles (worker, worker-2, worker-3, ...).
     * Returns at least the primary "worker" model if assigned, plus any numbered variants.
     * Used by SubAgentFactory for round-robin distribution across parallel workers.
     */
    public List<ChatModel> getWorkerModels() {
        var models = new ArrayList<ChatModel>();
        for (Map.Entry<String, UUID> entry : roleIndex.entrySet()) {
            if (entry.getKey().equals("worker") || entry.getKey().startsWith("worker-")) {
                try {
                    models.add(getByModelId(entry.getValue()));
                } catch (Exception e) {
                    log.warn("Failed to resolve worker model for role '{}': {}", entry.getKey(), e.getMessage());
                }
            }
        }
        return models;
    }

    /**
     * Evict a single cached ChatModel. Next access recreates it.
     */
    public void invalidateModel(UUID modelId) {
        ChatModel removed = modelCache.remove(modelId);
        if (removed != null) {
            log.info("Invalidated cached ChatModel: {}", modelId);
        }
    }

    /**
     * Evict all cached ChatModels for a provider.
     */
    public void invalidateProvider(UUID providerId) {
        List<ModelRecord> models = modelRepository.findByProviderId(providerId);
        int count = 0;
        for (ModelRecord model : models) {
            if (modelCache.remove(model.id()) != null) count++;
        }
        if (count > 0) {
            log.info("Invalidated {} cached ChatModels for provider {}", count, providerId);
        }
    }

    /**
     * Reload role → model mappings from database.
     */
    public void refreshRoles() {
        roleIndex.clear();
        List<ModelRoleRecord> roles = modelRoleRepository.findAll();
        for (ModelRoleRecord role : roles) {
            roleIndex.put(role.role(), role.modelId());
        }
        log.info("Refreshed role index: {} roles loaded", roles.size());
    }

    /**
     * Pre-create ChatModels for all role-assigned models at startup.
     * Errors during creation are logged and skipped (non-fatal).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refreshRoles();

        int created = 0;
        for (Map.Entry<String, UUID> entry : roleIndex.entrySet()) {
            try {
                getByModelId(entry.getValue());
                created++;
            } catch (Exception e) {
                log.warn("Failed to warm up ChatModel for role '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        log.info("ChatModelCache warm-up complete: {} models pre-created for {} roles",
                created, roleIndex.size());
    }

    /**
     * Current cache size (for monitoring).
     */
    public int size() {
        return modelCache.size();
    }

    /**
     * Current role count (for monitoring).
     */
    public int roleCount() {
        return roleIndex.size();
    }

    private ChatModel createChatModel(UUID modelId) {
        ModelRecord model = modelRepository.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelId));

        ProviderRecord provider = providerRepository.findById(model.providerId())
                .orElseThrow(() -> new IllegalArgumentException("Provider not found for model: " + modelId));

        return chatModelFactory.create(provider, model);
    }
}

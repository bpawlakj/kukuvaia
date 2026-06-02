package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.config.LlmProvidersProperties;
import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.model.ProviderRecord;
import ai.kukuvaia.provider.secret.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for dynamically created {@link ChatModel} instances.
 *
 * <p>Config-backed: providers, models and role assignments are read from
 * {@link LlmProvidersProperties} (bound to {@code kukuvaia.llm-providers.*} in
 * {@code application.yaml}) at startup. The database tables {@code providers},
 * {@code models} and {@code model_roles} are no longer consulted.
 *
 * <p>Lazy-creates {@link ChatModel} instances on first access via
 * {@link ChatModelFactory}. Invalidation methods are kept for API compat but
 * the config is immutable at runtime — no admin API changes it.
 */
@Component
public class ChatModelCache {

    private static final Logger log = LoggerFactory.getLogger(ChatModelCache.class);

    /** UUID → ChatModel, lazy-created on first access. */
    private final ConcurrentHashMap<UUID, ChatModel> modelCache = new ConcurrentHashMap<>();

    /** role name → model UUID (synthetic, deterministic from modelId string). */
    private final ConcurrentHashMap<String, UUID> roleIndex = new ConcurrentHashMap<>();

    /** modelId string → (ProviderRecord, ModelRecord) built once at startup. */
    private final Map<String, ProviderModelPair> configIndex = new HashMap<>();

    /** UUID → modelId string, for reverse lookup in createChatModel. */
    private final Map<UUID, String> uuidToModelId = new HashMap<>();

    private final ChatModelFactory chatModelFactory;
    private final LlmProvidersProperties providersProperties;
    private final SecretResolver secretResolver;

    public ChatModelCache(ChatModelFactory chatModelFactory,
                          LlmProvidersProperties providersProperties,
                          SecretResolver secretResolver) {
        this.chatModelFactory = chatModelFactory;
        this.providersProperties = providersProperties;
        this.secretResolver = secretResolver;
    }

    // ── Public API (interface unchanged) ─────────────────────────────────────

    /** Get or create a ChatModel by synthetic model UUID. */
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

    /** Get the model UUID assigned to a role. */
    public Optional<UUID> getModelIdForRole(String role) {
        return Optional.ofNullable(roleIndex.get(role));
    }

    /**
     * Get all ChatModels assigned to worker roles (worker, worker-2, worker-3, …).
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

    /** No-op — config is static; kept for call-site compatibility. */
    public void invalidateModel(UUID modelId) {
        modelCache.remove(modelId);
    }

    /** No-op — config is static; kept for call-site compatibility. */
    public void invalidateProvider(UUID providerId) {
        // nothing to invalidate — provider config does not change at runtime
    }

    /**
     * Reloads the role index from {@link LlmProvidersProperties}.
     * Called automatically in {@link #warmUp()}; idempotent.
     */
    public void refreshRoles() {
        roleIndex.clear();
        Map<String, String> roles = providersProperties.getRoles();
        if (roles == null || roles.isEmpty()) {
            log.warn("No role assignments in kukuvaia.llm-providers.roles — chat routing will fail");
            return;
        }
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            String role = entry.getKey();
            String modelId = entry.getValue();
            if (!configIndex.containsKey(modelId)) {
                log.warn("Role '{}' references unknown modelId '{}' — skipping", role, modelId);
                continue;
            }
            roleIndex.put(role, syntheticUuid(modelId));
        }
        log.info("Role index loaded from config: {} roles", roleIndex.size());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        buildConfigIndex();
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

    /** Current cache size (for monitoring). */
    public int size() { return modelCache.size(); }

    /** Current role count (for monitoring). */
    public int roleCount() { return roleIndex.size(); }

    /**
     * Resolve a synthetic model UUID back to the modelId string (e.g. "claude-sonnet-4-6").
     * Used by ModelRoutingAdvisor to resolve a UUID from the role index to the wire-level model ID.
     */
    public Optional<String> getModelIdString(UUID modelUuid) {
        return Optional.ofNullable(uuidToModelId.get(modelUuid));
    }

    /**
     * Look up a ModelRecord by modelId string.
     * Used by ContextCompactionAdvisor (contextWindow) and RoutingChatModel (model metadata).
     */
    public Optional<ModelRecord> getModelRecord(String modelId) {
        ProviderModelPair pair = configIndex.get(modelId);
        return pair == null ? Optional.empty() : Optional.of(pair.model());
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void buildConfigIndex() {
        configIndex.clear();
        uuidToModelId.clear();

        List<LlmProvidersProperties.ProviderDef> providers = providersProperties.getProviders();
        if (providers == null || providers.isEmpty()) {
            log.warn("No providers in kukuvaia.llm-providers.providers");
            return;
        }

        for (LlmProvidersProperties.ProviderDef pd : providers) {
            if (!pd.isEnabled()) continue;

            String resolvedKey;
            try {
                resolvedKey = secretResolver.resolve(pd.getApiKeyRef());
            } catch (SecretResolver.SecretNotFoundException e) {
                log.error("Cannot resolve apiKeyRef for provider '{}': {}", pd.getName(), e.getMessage());
                continue;
            }

            UUID providerId = syntheticUuid(pd.getName());
            ProviderRecord providerRecord = new ProviderRecord(
                    providerId,
                    pd.getName(),
                    pd.getType(),
                    pd.getBaseUrl(),
                    resolvedKey,
                    pd.isEnabled(),
                    0,
                    pd.getConfig() != null ? pd.getConfig() : Map.of(),
                    Instant.EPOCH,
                    Instant.EPOCH
            );

            if (pd.getModels() == null) continue;
            for (LlmProvidersProperties.ModelDef md : pd.getModels()) {
                UUID modelUuid = syntheticUuid(md.getModelId());
                ModelRecord modelRecord = new ModelRecord(
                        modelUuid,
                        providerId,
                        md.getModelId(),
                        md.getDisplayName() != null ? md.getDisplayName() : md.getModelId(),
                        md.getCapabilities() != null ? md.getCapabilities() : List.of(),
                        md.getTier(),
                        md.getMaxTokens(),
                        null,
                        true,
                        md.getConfig() != null ? md.getConfig() : Map.of(),
                        Instant.EPOCH,
                        Instant.EPOCH,
                        Instant.EPOCH
                );
                configIndex.put(md.getModelId(), new ProviderModelPair(providerRecord, modelRecord));
                uuidToModelId.put(modelUuid, md.getModelId());
            }
        }
        log.info("Config index built: {} model(s) configured", configIndex.size());
    }

    private ChatModel createChatModel(UUID modelId) {
        String modelIdStr = uuidToModelId.get(modelId);
        if (modelIdStr == null) {
            throw new IllegalArgumentException("No config entry for model UUID: " + modelId);
        }
        ProviderModelPair pair = configIndex.get(modelIdStr);
        if (pair == null) {
            throw new IllegalArgumentException("No provider/model pair for modelId: " + modelIdStr);
        }
        return chatModelFactory.create(pair.provider(), pair.model());
    }

    /**
     * Deterministic UUID from a string — stable across restarts, so the roleIndex
     * stays consistent if refreshRoles() is called more than once in a session.
     */
    private static UUID syntheticUuid(String input) {
        return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private record ProviderModelPair(ProviderRecord provider, ModelRecord model) {}
}

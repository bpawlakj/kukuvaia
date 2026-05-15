package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.provider.service.ChatModelCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM provider service — facade for model resolution.
 * Delegates to {@link ChatModelCache} for DB-backed model lookup.
 * Falls back to manually registered providers for backward compatibility.
 *
 * Resolution order:
 * 1. Try as role name ("advisor", "worker", "supervisor") via ChatModelCache
 * 2. Try as manually registered provider name (backward compat)
 * 3. Fallback to "supervisor" role
 */
@Component
public class LlmProviderService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderService.class);
    private static final String DEFAULT_ROLE = "supervisor";

    private final ChatModelCache chatModelCache;

    // Backward compat: manually registered providers (e.g., from /login or Spring auto-config)
    private final Map<String, ChatModel> legacyProviders = new ConcurrentHashMap<>();

    public LlmProviderService(ChatModelCache chatModelCache) {
        this.chatModelCache = chatModelCache;
    }

    /**
     * Register a provider manually (backward compat — used at startup or after /login).
     */
    public void register(String name, ChatModel chatModel) {
        legacyProviders.put(name, chatModel);
        log.info("Registered legacy LLM provider: {}", name);
    }

    /**
     * Resolve a ChatModel. Tries role-based lookup first, then legacy providers, then default.
     *
     * @param explicitProvider role name, provider name, or null for default
     * @param context          execution context (INTERACTIVE or DAEMON)
     */
    public ChatModel resolve(String explicitProvider, ExecutionContext context) {
        if (explicitProvider != null && !explicitProvider.isBlank()) {
            // 1. Try as role name via DB-backed cache
            ChatModel byRole = chatModelCache.getByRole(explicitProvider);
            if (byRole != null) {
                log.debug("Resolved '{}' as DB role for context {}", explicitProvider, context);
                return byRole;
            }

            // 2. Try as legacy registered provider
            ChatModel legacy = legacyProviders.get(explicitProvider);
            if (legacy != null) {
                log.debug("Resolved '{}' as legacy provider for context {}", explicitProvider, context);
                return legacy;
            }
        }

        // 3. Fallback: "supervisor" role from DB
        ChatModel defaultModel = chatModelCache.getByRole(DEFAULT_ROLE);
        if (defaultModel != null) {
            log.debug("Resolved '{}' role for context {}", DEFAULT_ROLE, context);
            return defaultModel;
        }

        // 4. Last resort: legacy "supervisor" or "default" provider
        ChatModel legacyDefault = legacyProviders.get("supervisor");
        if (legacyDefault == null) legacyDefault = legacyProviders.get("default");
        if (legacyDefault != null) {
            log.debug("Resolved legacy 'default' provider for context {}", context);
            return legacyDefault;
        }

        throw new ProviderNotAvailableException(
                explicitProvider != null ? explicitProvider : DEFAULT_ROLE);
    }

    /**
     * Resolve a ChatModel by routing role directly.
     *
     * @param role the role name (e.g., "advisor", "worker", "supervisor")
     * @return the ChatModel for the role
     * @throws ProviderNotAvailableException if role not assigned
     */
    public ChatModel resolveByRole(String role) {
        ChatModel model = chatModelCache.getByRole(role);
        if (model == null) {
            throw new ProviderNotAvailableException(role);
        }
        return model;
    }

    /**
     * Get all ChatModels assigned to worker roles (worker, worker-2, worker-3, ...).
     * Used for round-robin distribution in parallel worker execution.
     *
     * @return list of worker models (may be empty if no workers configured)
     */
    public List<ChatModel> resolveWorkerModels() {
        return chatModelCache.getWorkerModels();
    }

    public boolean isAvailable(String name) {
        return chatModelCache.getByRole(name) != null || legacyProviders.containsKey(name);
    }

    public static class ProviderNotAvailableException extends RuntimeException {
        public ProviderNotAvailableException(String provider) {
            super("LLM provider '%s' not configured or not authenticated".formatted(provider));
        }
    }
}

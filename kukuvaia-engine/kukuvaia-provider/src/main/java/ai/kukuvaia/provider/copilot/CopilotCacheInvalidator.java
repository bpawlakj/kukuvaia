package ai.kukuvaia.provider.copilot;

import ai.kukuvaia.provider.config.LlmProvidersProperties;
import ai.kukuvaia.provider.service.ChatModelCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link CopilotTokenRefreshedEvent} → {@link ChatModelCache} invalidation.
 *
 * <p>When Copilot mints a new bearer, the cached {@code ChatModel} (which captured
 * the old bearer at build time) must be evicted so the next access rebuilds it with
 * the fresh token. The event is fired by {@link ai.kukuvaia.provider.copilot.CopilotTokenManager}.
 */
@Component
public class CopilotCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CopilotCacheInvalidator.class);

    private final LlmProvidersProperties providersProperties;
    private final ChatModelCache chatModelCache;

    public CopilotCacheInvalidator(LlmProvidersProperties providersProperties, ChatModelCache chatModelCache) {
        this.providersProperties = providersProperties;
        this.chatModelCache = chatModelCache;
    }

    @EventListener
    public void onTokenRefreshed(CopilotTokenRefreshedEvent event) {
        int invalidated = 0;
        var providers = providersProperties.getProviders();
        if (providers == null) return;

        for (LlmProvidersProperties.ProviderDef provider : providers) {
            String ref = provider.getApiKeyRef();
            if (ref != null && ref.startsWith(CopilotSecretResolver.COPILOT_REF_PREFIX)) {
                // apiKeyRef is a synthetic UUID placeholder for copilot providers;
                // invalidate by evicting all models that came from this provider (by name UUID).
                var providerUuid = java.util.UUID.nameUUIDFromBytes(
                        provider.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                chatModelCache.invalidateProvider(providerUuid);
                invalidated++;
            }
        }
        if (invalidated > 0) {
            log.info("[copilot] Invalidated {} provider(s) after token refresh", invalidated);
        }
    }
}

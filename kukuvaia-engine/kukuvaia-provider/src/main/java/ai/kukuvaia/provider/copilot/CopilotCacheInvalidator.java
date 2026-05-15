package ai.kukuvaia.provider.copilot;

import ai.kukuvaia.provider.service.ChatModelCache;
import ai.kukuvaia.provider.model.ProviderRecord;
import ai.kukuvaia.provider.repository.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ai.kukuvaia.provider.service.ChatModelFactory;

/**
 * Bridges {@link CopilotTokenRefreshedEvent} → {@link ChatModelCache} invalidation.
 *
 * <p>The {@code OpenAiApi} captures the bearer string at build time, so when Copilot
 * mints a new bearer the cached {@code ChatModel} silently keeps using the old one
 * (401 on next request). Invalidating cached models tied to any provider whose
 * {@code api_key_ref} starts with {@code copilot:} forces a rebuild via
 * {@code ChatModelFactory} on the next access, picking up the fresh bearer.
 *
 * <p>Kept in this package so {@code ChatModelCache} stays provider-agnostic.
 */
@Component
public class CopilotCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CopilotCacheInvalidator.class);

    private final ProviderRepository providerRepository;
    private final ChatModelCache chatModelCache;

    public CopilotCacheInvalidator(ProviderRepository providerRepository, ChatModelCache chatModelCache) {
        this.providerRepository = providerRepository;
        this.chatModelCache = chatModelCache;
    }

    @EventListener
    public void onTokenRefreshed(CopilotTokenRefreshedEvent event) {
        int invalidated = 0;
        for (ProviderRecord provider : providerRepository.findAll()) {
            String ref = provider.apiKeyRef();
            if (ref != null && ref.startsWith(CopilotSecretResolver.COPILOT_REF_PREFIX)) {
                chatModelCache.invalidateProvider(provider.id());
                invalidated++;
            }
        }
        if (invalidated > 0) {
            log.info("[copilot] Invalidated {} cached ChatModel(s) after token refresh", invalidated);
        }
    }
}

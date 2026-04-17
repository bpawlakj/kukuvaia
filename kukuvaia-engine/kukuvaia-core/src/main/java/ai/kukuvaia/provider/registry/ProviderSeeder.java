package ai.kukuvaia.provider.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds a default provider from environment variables on first boot.
 * Only runs when the providers table is empty AND LLM_BASE_URL + LLM_API_KEY are set.
 * Ensures backward compatibility — existing env-var config works without manual DB setup.
 *
 * Runs before {@link ChatModelCache#warmUp()} (lower order = earlier).
 */
@Component
public class ProviderSeeder {

    private static final Logger log = LoggerFactory.getLogger(ProviderSeeder.class);

    private static final String DEFAULT_PROVIDER_NAME = "default";
    private static final String DEFAULT_MODEL_NAME = "claude-sonnet-4.5";
    private static final String DEFAULT_ROLE = "supervisor";

    private final ProviderRepository providerRepository;
    private final ModelRepository modelRepository;
    private final ModelRoleRepository modelRoleRepository;

    public ProviderSeeder(ProviderRepository providerRepository,
                          ModelRepository modelRepository,
                          ModelRoleRepository modelRoleRepository) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.modelRoleRepository = modelRoleRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0) // Run before ChatModelCache.warmUp()
    public void seedFromEnvironment() {
        if (providerRepository.count() > 0) {
            log.debug("Providers table not empty — skipping seed");
            return;
        }

        String baseUrl = System.getenv("LLM_BASE_URL");
        String apiKeyRef = resolveApiKeyRef();

        if (baseUrl == null || baseUrl.isBlank() || apiKeyRef == null) {
            log.info("No LLM_BASE_URL or API key env var found — skipping provider seed");
            return;
        }

        String modelName = System.getenv("LLM_MODEL");
        if (modelName == null || modelName.isBlank()) {
            modelName = DEFAULT_MODEL_NAME;
        }

        log.info("Seeding default provider from env vars: baseUrl={}, model={}", baseUrl, modelName);

        // Create provider — apiKeyRef stores the env var NAME, not the value
        ProviderRecord provider = providerRepository.save(
                DEFAULT_PROVIDER_NAME, "custom", baseUrl, apiKeyRef, 0, java.util.Map.of());

        // Create model
        ModelRecord model = modelRepository.save(
                provider.id(), modelName, modelName,
                java.util.List.of("text", "code"), "standard",
                4096, null, null);

        // Assign as default role
        modelRoleRepository.upsert(DEFAULT_ROLE, model.id(), "Auto-seeded from environment variables");

        log.info("Seeded default provider: provider={}, model={}, role={}",
                provider.name(), model.modelId(), DEFAULT_ROLE);
    }

    /**
     * Find the env var name that holds the API key.
     * Checks LLM_API_KEY first, then SMARTGATE_API_KEY, then OPENAI_API_KEY.
     */
    private String resolveApiKeyRef() {
        if (isEnvSet("LLM_API_KEY")) return "LLM_API_KEY";
        if (isEnvSet("SMARTGATE_API_KEY")) return "SMARTGATE_API_KEY";
        if (isEnvSet("OPENAI_API_KEY")) return "OPENAI_API_KEY";
        return null;
    }

    private boolean isEnvSet(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}

package ai.kukuvaia.agents.config

import ai.kukuvaia.provider.service.ChatModelCache
import ai.kukuvaia.provider.repository.ModelRoleRepository
import com.embabel.common.ai.model.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Bridges kukuvaia's DB-backed model registry to Embabel's ModelProvider.
 *
 * Uses [KukuvaiaModelProvider] — a custom ModelProvider that:
 * - Starts empty (no models at boot — user hasn't registered any yet)
 * - Gets refreshed when providers/models are registered via API
 * - Does NOT crash on startup like ConfigurableModelProvider does with empty state
 *
 * Lifecycle:
 * 1. App boots → KukuvaiaModelProvider created empty (or with fallback ChatModel)
 * 2. User registers provider + models via /api/providers, /api/models
 * 3. User assigns roles via /api/models/roles
 * 4. ChatModelCache.warmUp() or manual refresh → KukuvaiaModelProvider.refresh()
 * 5. Embabel agents can now use ctx.ai().withLlmByRole("cheapest")
 */
@Configuration
class EmbabelModelBridgeConfig(
    private val chatModelCache: ChatModelCache,
    private val modelRoleRepository: ModelRoleRepository,
) {

    private val log = LoggerFactory.getLogger(EmbabelModelBridgeConfig::class.java)

    /**
     * The only ModelProvider in the context.
     * Embabel's default modelProvider is removed by EmbabelBeanOverride.
     * Starts with supervisor model from cache (or null if DB empty), refreshed at startup.
     */
    @Bean
    fun kukuvaiaModelProvider(): KukuvaiaModelProvider {
        // Use supervisor from cache as fallback — avoids injecting @Primary RoutingChatModel
        val supervisorModel = chatModelCache.getByRole("supervisor")
        val fallbackLlm = if (supervisorModel != null) {
            createLlm("fallback", supervisorModel)
        } else {
            null
        }
        val provider = KukuvaiaModelProvider(fallbackLlm)

        // Try to load from DB (may be empty on first boot)
        refreshProvider(provider)

        return provider
    }

    /**
     * Reload models from DB into the ModelProvider.
     * Called at startup and can be called again after provider/model registration.
     */
    fun refreshProvider(provider: KukuvaiaModelProvider) {
        val roles = modelRoleRepository.findAll()
        if (roles.isEmpty()) {
            log.info("No DB model roles found — Embabel using fallback ChatModel")
            return
        }

        val llms = mutableListOf<Llm>()
        val roleMap = mutableMapOf<String, String>()
        var defaultLlmName: String? = null

        for (role in roles) {
            try {
                val chatModel = chatModelCache.getByModelId(role.modelId())
                val llmName = role.role()

                llms.add(createLlm(llmName, chatModel))

                when (role.role()) {
                    "worker", "cheapest" -> roleMap[ModelProvider.CHEAPEST_ROLE] = llmName
                    "advisor", "powerful", "best" -> roleMap[ModelProvider.BEST_ROLE] = llmName
                    "supervisor" -> defaultLlmName = llmName
                }
                roleMap[role.role()] = llmName
            } catch (e: Exception) {
                log.warn("Failed to create Embabel Llm for role '{}': {}", role.role(), e.message)
            }
        }

        if (llms.isNotEmpty()) {
            provider.updateModels(llms, roleMap, defaultLlmName ?: llms.first().name)
            log.info("Embabel ModelProvider refreshed: {} models, {} roles", llms.size, roleMap.size)
        }
    }

    private fun createLlm(name: String, chatModel: ChatModel): Llm = Llm(
        name, "kukuvaia", chatModel, DefaultOptionsConverter, null, listOf(), null,
    )
}

/**
 * ModelProvider that tolerates empty state and supports runtime updates.
 * Unlike ConfigurableModelProvider, this does NOT throw when no models are configured.
 */
/**
 * ModelProvider that tolerates empty state and supports runtime updates.
 * Uses a simple map-based lookup instead of ConfigurableModelProvider
 * (which has a ClassCastException bug in Embabel 0.3.4).
 */
class KukuvaiaModelProvider(
    private val fallbackLlm: Llm?,
) : ModelProvider {

    private val log = LoggerFactory.getLogger(KukuvaiaModelProvider::class.java)

    @Volatile
    private var llmsByName: Map<String, Llm> = emptyMap()

    @Volatile
    private var roleMap: Map<String, String> = emptyMap()

    @Volatile
    private var defaultLlmName: String? = null

    /**
     * Replace the model set at runtime. Thread-safe via volatile reference swap.
     */
    fun updateModels(llms: List<Llm>, roles: Map<String, String>, defaultLlm: String) {
        llmsByName = llms.associateBy { it.name }
        roleMap = roles
        defaultLlmName = defaultLlm
    }

    override fun getLlm(criteria: ModelSelectionCriteria): Llm {
        val models = llmsByName
        if (models.isEmpty()) {
            if (fallbackLlm != null) return fallbackLlm
            throw NoSuitableModelException(criteria, emptyList())
        }

        // Try role-based lookup
        if (criteria is ByRoleModelSelectionCriteria) {
            val roleName = criteria.role
            val mappedName = roleMap[roleName]
            if (mappedName != null) {
                val llm = models[mappedName]
                if (llm != null) return llm
            }
            val direct = models[roleName]
            if (direct != null) return direct
        }

        // Try name-based lookup
        if (criteria is ByNameModelSelectionCriteria) {
            val llm = models[criteria.name]
            if (llm != null) return llm
        }

        // Fallback to default
        val defName = defaultLlmName
        if (defName != null) {
            val defLlm = models[defName]
            if (defLlm != null) return defLlm
        }

        return models.values.first()
    }

    override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService {
        throw NoSuitableModelException(criteria, emptyList())
    }

    override fun listRoles(type: Class<out AiModel<*>>): List<String> {
        return roleMap.keys.toList().ifEmpty { listOf("fallback") }
    }

    override fun listModelNames(type: Class<out AiModel<*>>): List<String> {
        return llmsByName.keys.toList().ifEmpty { listOf("fallback") }
    }

    override fun listModels(): List<ModelMetadata> = emptyList()

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return if (llmsByName.isEmpty()) {
            "KukuvaiaModelProvider (no models configured — register a provider first)"
        } else {
            "KukuvaiaModelProvider (${llmsByName.size} models, ${roleMap.size} roles)"
        }
    }
}

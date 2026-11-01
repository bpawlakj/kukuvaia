package ai.kukuvaia.agents.config

import ai.kukuvaia.agents.daemon.DaemonEmbabelRuns
import ai.kukuvaia.provider.config.LlmProvidersProperties
import ai.kukuvaia.provider.service.ChatModelCache
import ai.kukuvaia.provider.service.LlmProviderService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Bridges kukuvaia's config-backed model registry to Embabel's ModelProvider.
 *
 * Uses [KukuvaiaModelProvider] — a custom ModelProvider that:
 * - Starts with models from [ChatModelCache] (populated at startup from application.yaml)
 * - Does NOT crash on startup with empty state
 *
 * Lifecycle:
 * 1. App boots → ChatModelCache.warmUp() populates the cache from LlmProvidersProperties
 * 2. This config reads roles from LlmProvidersProperties and models from ChatModelCache
 * 3. Embabel agents can use ctx.ai().withLlmByRole("cheapest")
 *
 * Every model is wrapped in [GuardedChatModel]: Embabel calls `ChatModel` directly, so the guards
 * kukuvaia's ChatClients get from advisors (tool-results-are-data boundary, audit metrics, daemon
 * Copilot policy) are applied there.
 */
@Configuration
class EmbabelModelBridgeConfig(
    private val chatModelCache: ChatModelCache,
    private val providersProperties: LlmProvidersProperties,
    private val daemonRuns: DaemonEmbabelRuns,
    private val meterRegistry: ObjectProvider<MeterRegistry>,
) {

    private val log = LoggerFactory.getLogger(EmbabelModelBridgeConfig::class.java)

    @Bean
    fun kukuvaiaModelProvider(): KukuvaiaModelProvider {
        val supervisorModel = chatModelCache.getByRole("supervisor")
        val fallbackLlm = if (supervisorModel != null) {
            createLlm("fallback", supervisorModel)
        } else {
            null
        }
        val provider = KukuvaiaModelProvider(fallbackLlm)
        refreshProvider(provider)
        return provider
    }

    fun refreshProvider(provider: KukuvaiaModelProvider) {
        val roles = providersProperties.roles
        if (roles.isNullOrEmpty()) {
            log.info("No roles configured in llm-providers — Embabel using fallback ChatModel")
            return
        }

        val llms = mutableListOf<SpringAiLlmService>()
        val roleMap = mutableMapOf<String, String>()
        var defaultLlmName: String? = null

        for ((roleName, _) in roles) {
            try {
                val chatModel = chatModelCache.getByRole(roleName) ?: continue
                llms.add(createLlm(roleName, chatModel))

                when (roleName) {
                    "worker", "cheapest" -> roleMap[ModelProvider.CHEAPEST_ROLE] = roleName
                    "advisor", "powerful", "best" -> roleMap[ModelProvider.BEST_ROLE] = roleName
                    "supervisor" -> defaultLlmName = roleName
                }
                roleMap[roleName] = roleName
            } catch (e: Exception) {
                log.warn("Failed to create Embabel Llm for role '{}': {}", roleName, e.message)
            }
        }

        if (llms.isNotEmpty()) {
            provider.updateModels(llms, roleMap, defaultLlmName ?: llms.first().name)
            log.info("Embabel ModelProvider refreshed: {} models, {} roles", llms.size, roleMap.size)
        }
    }

    // Embabel 1.5 replaced the deprecated `Llm` class with `SpringAiLlmService`.
    private fun createLlm(name: String, chatModel: ChatModel): SpringAiLlmService {
        val role = if (name == "fallback") "supervisor" else name
        val copilot = chatModelCache.getProviderForRole(role).map { LlmProviderService.isCopilot(it) }.orElse(null)
        val guarded = GuardedChatModel(name, chatModel, copilot, daemonRuns, meterRegistry.ifAvailable)
        return SpringAiLlmService(name, "kukuvaia", guarded, DefaultOptionsConverter)
    }
}

/**
 * ModelProvider that tolerates empty state and supports runtime updates.
 */
class KukuvaiaModelProvider(
    private val fallbackLlm: SpringAiLlmService?,
) : ModelProvider {

    private val log = LoggerFactory.getLogger(KukuvaiaModelProvider::class.java)

    @Volatile private var llmsByName: Map<String, SpringAiLlmService> = emptyMap()
    @Volatile private var roleMap: Map<String, String> = emptyMap()
    @Volatile private var defaultLlmName: String? = null

    fun updateModels(llms: List<SpringAiLlmService>, roles: Map<String, String>, defaultLlm: String) {
        llmsByName = llms.associateBy { it.name }
        roleMap = roles
        defaultLlmName = defaultLlm
    }

    override fun getLlm(criteria: ModelSelectionCriteria): SpringAiLlmService {
        val models = llmsByName
        if (models.isEmpty()) {
            if (fallbackLlm != null) return fallbackLlm
            throw NoSuitableModelException(criteria, emptyList())
        }

        if (criteria is ByRoleModelSelectionCriteria) {
            val roleName = criteria.role
            val mappedName = roleMap[roleName]
            if (mappedName != null) { models[mappedName]?.let { return it } }
            models[roleName]?.let { return it }
        }

        if (criteria is ByNameModelSelectionCriteria) {
            models[criteria.name]?.let { return it }
        }

        defaultLlmName?.let { models[it]?.let { llm -> return llm } }
        return models.values.first()
    }

    override fun getEmbeddingService(criteria: ModelSelectionCriteria): EmbeddingService {
        throw NoSuitableModelException(criteria, emptyList())
    }

    override fun listRoles(modelClass: Class<*>): List<String> =
        roleMap.keys.toList().ifEmpty { listOf("fallback") }

    override fun listModelNames(modelClass: Class<*>): List<String> =
        llmsByName.keys.toList().ifEmpty { listOf("fallback") }

    override fun listModels(): List<ModelMetadata> = emptyList()

    override fun infoString(verbose: Boolean?, indent: Int): String =
        if (llmsByName.isEmpty()) "KukuvaiaModelProvider (no models configured)"
        else "KukuvaiaModelProvider (${llmsByName.size} models, ${roleMap.size} roles)"
}

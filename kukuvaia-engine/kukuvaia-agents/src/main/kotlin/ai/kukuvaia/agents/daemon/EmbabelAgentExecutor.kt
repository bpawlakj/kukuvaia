package ai.kukuvaia.agents.daemon

import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition
import ai.kukuvaia.agent.daemon.exec.DaemonExecutor
import ai.kukuvaia.agent.daemon.exec.ExecutionFailedException
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome
import ai.kukuvaia.agent.daemon.exec.JobInvocation
import ai.kukuvaia.agent.daemon.exec.JobKind
import ai.kukuvaia.agent.daemon.exec.JobLimits
import ai.kukuvaia.agents.config.KukuvaiaModelProvider
import ai.kukuvaia.provider.service.LlmProviderService
import com.embabel.agent.core.Agent as AgentDefinition
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Budget
import com.embabel.agent.core.EarlyTermination
import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.ModelProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `kind: embabel` — runs a deployed Embabel (GOAP) agent, selected by `target` = agent name.
 *
 * Limits map onto Embabel's own controls, the guarded model path and the runtime's hard deadline:
 * - `token_budget` → `Budget.tokens` (checked between actions) **and** a per-run counter in
 *   `GuardedChatModel` (checked before every model call, so the cap also holds inside one action);
 * - `max_actions` → `Budget.actions`;
 * - the job deadline → a policy checked between actions, a refusal of every model call after it, and on
 *   expiry/cancellation the process is killed (`AgentPlatform.killAgentProcess`).
 *
 * Input is bound as a single [UserInput] built from the rendered prompt (declared, sanitized fields only).
 * No kukuvaia tools are exposed, and agents declaring Embabel tool groups are rejected (v1).
 * Usage = max(guard-counted tokens, `AgentProcess.totalUsage()`); a killed or timed-out run is charged at
 * least its `token_budget` (an in-flight HTTP call cannot be cancelled and may still spend).
 * Audit error texts are fixed strings — details (which may echo prompts) stay in server logs.
 */
@Component
class EmbabelAgentExecutor(
    private val platform: ObjectProvider<AgentPlatform>,
    private val runs: DaemonEmbabelRuns,
    private val objectMapper: ObjectProvider<ObjectMapper>,
    private val modelProviders: ObjectProvider<ModelProvider>,
    private val applicationContext: ObjectProvider<org.springframework.context.ApplicationContext>,
) : DaemonExecutor {

    private val log = LoggerFactory.getLogger(EmbabelAgentExecutor::class.java)

    override fun kind(): JobKind = JobKind.EMBABEL

    override fun validate(definition: DaemonJobDefinition): Optional<String> {
        unguardedModelProvider()?.let { return Optional.of(it) }
        val provider = definition.provider()
        if (provider != null && !LlmProviderService.isCopilotName(provider)) {
            return Optional.of("provider '$provider' is not supported for embabel jobs — models come from the agent's " +
                    "roles; only 'provider: copilot' (explicit Copilot opt-in) is accepted")
        }
        val agent = knownAgents().firstOrNull { it.name == definition.target() }
            ?: return Optional.of("no Embabel agent named '${definition.target()}' (available: ${agentNames()})")
        val toolGroups = agent.actions.flatMap { it.toolGroups }.map { it.role }.distinct()
        if (toolGroups.isNotEmpty()) {
            return Optional.of("Embabel agent '${agent.name}' requests tool groups $toolGroups — no tools are exposed to daemon agents (v1)")
        }
        return Optional.empty()
    }

    fun agentNames(): List<String> = knownAgents().map { it.name }.sorted()

    /**
     * Agents the platform has deployed — or, while it has not deployed them yet (job definitions are
     * validated during context startup, before Embabel deploys), the `@Agent` beans read the way Embabel
     * reads them, so names and declared tool groups are the same.
     */
    private fun knownAgents(): List<AgentDefinition> {
        val deployed = platform.ifAvailable?.agents().orEmpty()
        if (deployed.isNotEmpty()) return deployed
        val ctx = applicationContext.ifAvailable ?: return emptyList()
        val reader = com.embabel.agent.api.annotation.support.AgentMetadataReader()
        return ctx.getBeansWithAnnotation(com.embabel.agent.api.annotation.Agent::class.java).values.mapNotNull {
            try {
                reader.createAgentMetadata(it) as? AgentDefinition
            } catch (e: RuntimeException) {
                log.warn("Could not read Embabel agent metadata of {}: {}", it.javaClass.simpleName, e.message)
                null
            }
        }
    }

    /** All models Embabel can use must come from the guarded provider — otherwise refuse (fail closed). */
    private fun unguardedModelProvider(): String? {
        val foreign = modelProviders.stream().toList().filter { it !is KukuvaiaModelProvider }
        return if (foreign.isEmpty()) null
        else "Embabel model provider(s) ${foreign.map { it.javaClass.simpleName }} are not kukuvaia's guarded provider — embabel jobs disabled"
    }

    override fun execute(invocation: JobInvocation, limits: JobLimits): ExecutionOutcome {
        unguardedModelProvider()?.let { throw IllegalStateException(it) }
        val agentPlatform = platform.getObject()
        val agent = agentPlatform.agents().firstOrNull { it.name == invocation.target() }
            ?: throw IllegalArgumentException("No Embabel agent named '${invocation.target()}'")

        val tokenBudget = limits.tokenBudget().takeIf { it > 0 } ?: Budget.DEFAULT_TOKEN_LIMIT
        val budget = Budget(
            actions = limits.maxActions().takeIf { it > 0 } ?: Budget.DEFAULT_ACTION_LIMIT,
            tokens = tokenBudget,
        )
        // Embabel does not derive termination from ProcessOptions.budget by itself, and (1.5.4)
        // ProcessOptions.withAdditionalEarlyTerminationPolicy *replaces* the policy instead of adding it —
        // so compose budget + deadline ourselves and add them to the default ProcessControl policy.
        val defaults = ProcessOptions().withBudget(budget)
        val options = defaults.withProcessControl(defaults.processControl.withAdditionalEarlyTerminationPolicy(
            EarlyTerminationPolicy.firstOf(budget.earlyTerminationPolicy(), DeadlinePolicy(invocation.deadline()))))
        val bindings = mapOf(IoBinding.DEFAULT_BINDING to UserInput(invocation.prompt() ?: ""))
        val process = agentPlatform.createAgentProcess(agent, options, bindings)
        val policy = DaemonEmbabelRuns.RunPolicy(invocation.jobName(),
            copilotAllowed = LlmProviderService.isCopilotName(invocation.provider()),
            deadline = invocation.deadline(), tokenBudget = tokenBudget, maxTokens = limits.maxTokens(),
            lifetime = invocation.lifetime())
        val processWork = invocation.lifetime().register()
        runs.register(process.id, policy)

        var future: CompletableFuture<AgentProcess>? = null
        try {
            future = agentPlatform.start(process)
            future.whenComplete { _, _ -> processWork.close() }
            try {
                // Wait in short slices: a guard refusal (provider policy, token cap) must stop the process at
                // once (Embabel would otherwise retry the failing action until the deadline).
                while (true) {
                    val remaining = Duration.between(Instant.now(), invocation.deadline()).toMillis()
                    if (remaining <= 0) {
                        stop(agentPlatform, process, policy)
                        throw failure(process, policy, "deadline exceeded — Embabel agent process killed",
                            timedOut = true, killed = true, tokenBudget = tokenBudget)
                    }
                    try {
                        future.get(minOf(remaining, POLL_MILLIS), TimeUnit.MILLISECONDS)
                        break
                    } catch (e: TimeoutException) {
                        policy.refusal?.let {
                            stop(agentPlatform, process, policy)
                            throw failure(process, policy, it, timedOut = false, killed = true, tokenBudget = tokenBudget)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                stop(agentPlatform, process, policy)
                Thread.currentThread().interrupt()
                throw e
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                // A guard refusal is our own fixed text (role, job, reason) — report it as such.
                policy.refusal?.let { throw failure(process, policy, it, timedOut = false, killed = false, tokenBudget = tokenBudget) }
                log.warn("Embabel agent '{}' (job '{}') failed", agent.name, invocation.jobName(), cause)
                throw failure(process, policy, "Embabel agent failed: ${cause.javaClass.simpleName}",
                    timedOut = false, killed = false, tokenBudget = tokenBudget)
            }
            policy.stopped = true
            policy.refusal?.let { throw failure(process, policy, it, timedOut = false, killed = false, tokenBudget = tokenBudget) }
            return when (process.status) {
                AgentProcessStatusCode.COMPLETED -> outcome(process, policy)
                AgentProcessStatusCode.TERMINATED -> {
                    val termination = process.failureInfo as? EarlyTermination
                    throw failure(process, policy, "Embabel agent terminated early: ${terminationLabel(termination)}",
                        timedOut = termination?.policy is DeadlinePolicy, killed = false, tokenBudget = tokenBudget)
                }
                else -> {
                    log.warn("Embabel agent '{}' (job '{}') ended {}: {}", agent.name, invocation.jobName(), process.status, process.failureInfo)
                    throw failure(process, policy, "Embabel agent ended ${process.status}", timedOut = false, killed = false,
                        tokenBudget = tokenBudget)
                }
            }
        } finally {
            policy.stopped = true
            // Keep the run registered until its process has really ended, so late calls are still refused.
            val f = future
            if (f == null) processWork.close()
            if (f == null || f.isDone) runs.unregister(process.id)
            else f.whenComplete { _, _ -> runs.unregister(process.id) }
        }
    }

    private fun outcome(process: AgentProcess, policy: DaemonEmbabelRuns.RunPolicy): ExecutionOutcome {
        val usage = usageOf(process, policy)
        val result = process.lastResult()
        val text = when (result) {
            null -> "(no result)"
            is String -> result
            else -> result.toString()
        }
        val output: JsonNode? = result?.takeUnless { it is String }?.let {
            try {
                objectMapper.ifAvailable?.valueToTree<JsonNode>(it)
            } catch (e: RuntimeException) {
                log.debug("Embabel result of {} is not JSON-serializable: {}", process.agent.name, e.message)
                null
            }
        }
        return ExecutionOutcome(text, output, usage.prompt, usage.completion, usage.reported, "embabel", usage.models)
    }

    private fun failure(process: AgentProcess, policy: DaemonEmbabelRuns.RunPolicy, message: String,
                        timedOut: Boolean, killed: Boolean, tokenBudget: Int): ExecutionFailedException {
        val usage = usageOf(process, policy)
        if (killed) {
            // An in-flight model call may still complete and spend after the kill: charge at least the budget.
            val total = maxOf(usage.prompt + usage.completion, tokenBudget)
            return ExecutionFailedException(message, total - usage.completion, usage.completion, true, timedOut)
        }
        return ExecutionFailedException(message, usage.prompt, usage.completion, usage.reported, timedOut)
    }

    private data class UsageSummary(val prompt: Int, val completion: Int, val reported: Boolean, val models: String?)

    /**
     * Usage = max(tokens counted by the guard, Embabel's recorded usage). Reported only if no guarded call
     * lacked usage and Embabel recorded usage for every invocation (or no model was called at all).
     */
    private fun usageOf(process: AgentProcess, policy: DaemonEmbabelRuns.RunPolicy): UsageSummary {
        val invocations = process.llmInvocations
        val embabelReported = invocations.all { it.usage.promptTokens != null || it.usage.completionTokens != null }
        val reported = policy.unmetered.get() == 0 && embabelReported &&
                (policy.calls.get() == 0 || invocations.isNotEmpty() || policy.tokens.get() > 0)
        val total = process.totalUsage()
        var prompt = total.promptTokens ?: 0
        val completion = total.completionTokens ?: 0
        val guardCounted = policy.tokens.get()
        if (guardCounted > prompt + completion) prompt = guardCounted - completion
        val models = process.totalModelsUsed().map { it.name }.distinct().joinToString(",").ifBlank { null }
        return UsageSummary(prompt, completion, reported, models)
    }

    /** Fixed vocabulary only — Embabel's reason strings may carry model or blackboard content. */
    private fun terminationLabel(termination: EarlyTermination?): String = when (termination?.policy) {
        null -> "unknown policy"
        is DeadlinePolicy -> "job deadline reached"
        else -> termination.policy.name.ifBlank { termination.policy.javaClass.simpleName }
    }

    private fun stop(agentPlatform: AgentPlatform, process: AgentProcess, policy: DaemonEmbabelRuns.RunPolicy) {
        policy.stopped = true
        try {
            agentPlatform.killAgentProcess(process.id)
        } catch (e: RuntimeException) {
            log.warn("Could not kill Embabel process {}: {}", process.id, e.message)
        }
    }

    private companion object {
        const val POLL_MILLIS = 100L
    }

    /** Stops the process between actions once the job deadline has passed. */
    private class DeadlinePolicy(private val deadline: Instant) : EarlyTerminationPolicy {
        override val name: String get() = "kukuvaia-daemon-deadline"

        override fun shouldTerminate(agentProcess: AgentProcess): EarlyTermination? =
            if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted) {
                EarlyTermination(agentProcess, true, "job deadline reached", this)
            } else null
    }
}

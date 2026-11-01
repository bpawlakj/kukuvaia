package ai.kukuvaia.agents.daemon

import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition
import ai.kukuvaia.agent.daemon.exec.DaemonExecutor
import ai.kukuvaia.agent.daemon.exec.ExecutionFailedException
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome
import ai.kukuvaia.agent.daemon.exec.JobInvocation
import ai.kukuvaia.agent.daemon.exec.JobKind
import ai.kukuvaia.agent.daemon.exec.JobLimits
import ai.kukuvaia.provider.service.LlmProviderService
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Budget
import com.embabel.agent.core.EarlyTermination
import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * `kind: embabel` — runs a deployed Embabel (GOAP) agent, selected by `target` = agent name.
 *
 * Limits map onto Embabel's own controls plus the runtime's hard deadline:
 * - `token_budget` → `Budget.tokens`, `max_actions` → `Budget.actions` (Embabel's early-termination policies);
 * - the job deadline → an extra [EarlyTerminationPolicy] checked between actions, and on expiry or
 *   cancellation the process is killed (`AgentPlatform.killAgentProcess`).
 *
 * Input is bound as a single [UserInput] built from the rendered prompt (declared, sanitized fields only).
 * No kukuvaia tools are exposed to Embabel agents (v1). LLM calls go through `GuardedChatModel`, which
 * enforces the daemon provider policy for the processes registered in [DaemonEmbabelRuns].
 * Usage = `AgentProcess.totalUsage()`; a run stopped mid-way reports its real usage via
 * [ExecutionFailedException] (unreported usage still falls back to the conservative charge).
 */
@Component
class EmbabelAgentExecutor(
    private val platform: ObjectProvider<AgentPlatform>,
    private val runs: DaemonEmbabelRuns,
    private val objectMapper: ObjectProvider<ObjectMapper>,
) : DaemonExecutor {

    private val log = LoggerFactory.getLogger(EmbabelAgentExecutor::class.java)

    override fun kind(): JobKind = JobKind.EMBABEL

    override fun validate(definition: DaemonJobDefinition): Optional<String> {
        val provider = definition.provider()
        if (provider != null && !LlmProviderService.isCopilotName(provider)) {
            return Optional.of("provider '$provider' is not supported for embabel jobs — models come from the agent's " +
                    "roles; only 'provider: copilot' (explicit Copilot opt-in) is accepted")
        }
        val names = agentNames()
        return if (definition.target() in names) Optional.empty()
        else Optional.of("no Embabel agent named '${definition.target()}' (available: $names)")
    }

    fun agentNames(): List<String> = platform.ifAvailable?.agents()?.map { it.name }?.sorted() ?: emptyList()

    override fun execute(invocation: JobInvocation, limits: JobLimits): ExecutionOutcome {
        val agentPlatform = platform.getObject()
        val agent = agentPlatform.agents().firstOrNull { it.name == invocation.target() }
            ?: throw IllegalArgumentException("No Embabel agent named '${invocation.target()}'")

        val budget = Budget(
            actions = limits.maxActions().takeIf { it > 0 } ?: Budget.DEFAULT_ACTION_LIMIT,
            tokens = limits.tokenBudget().takeIf { it > 0 } ?: Budget.DEFAULT_TOKEN_LIMIT,
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
            copilotAllowed = LlmProviderService.isCopilotName(invocation.provider()))
        runs.register(process.id, policy)
        try {
            val future = agentPlatform.start(process)
            try {
                // Wait in short slices: a provider-policy refusal must stop the process at once
                // (Embabel would otherwise retry the failing action until the deadline).
                while (true) {
                    val remaining = Duration.between(Instant.now(), invocation.deadline()).toMillis()
                    if (remaining <= 0) {
                        kill(agentPlatform, process)
                        throw failure(process, "deadline exceeded — Embabel agent process killed", timedOut = true)
                    }
                    try {
                        future.get(minOf(remaining, POLL_MILLIS), TimeUnit.MILLISECONDS)
                        break
                    } catch (e: TimeoutException) {
                        policy.refusal?.let {
                            kill(agentPlatform, process)
                            throw failure(process, it, timedOut = false)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                kill(agentPlatform, process)
                Thread.currentThread().interrupt()
                throw e
            } catch (e: ExecutionException) {
                throw failure(process, "Embabel agent failed: ${(e.cause ?: e).message}", timedOut = false)
            }
            policy.refusal?.let { throw failure(process, it, timedOut = false) }
            return when (process.status) {
                AgentProcessStatusCode.COMPLETED -> outcome(process)
                AgentProcessStatusCode.TERMINATED ->
                    throw failure(process, "Embabel agent terminated early: ${terminationReason(process)}",
                        timedOut = (process.failureInfo as? EarlyTermination)?.policy is DeadlinePolicy)
                else -> throw failure(process, "Embabel agent ended ${process.status}" +
                        (process.failureInfo?.let { ": $it" } ?: ""), timedOut = false)
            }
        } finally {
            runs.unregister(process.id)
        }
    }

    private fun outcome(process: AgentProcess): ExecutionOutcome {
        val usage = usageOf(process)
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

    private fun failure(process: AgentProcess, message: String, timedOut: Boolean): ExecutionFailedException {
        val usage = usageOf(process)
        return ExecutionFailedException(message, usage.prompt, usage.completion, usage.reported, timedOut)
    }

    private data class UsageSummary(val prompt: Int, val completion: Int, val reported: Boolean, val models: String?)

    private fun usageOf(process: AgentProcess): UsageSummary {
        val invocations = process.llmInvocations
        val reported = invocations.all { it.usage.promptTokens != null || it.usage.completionTokens != null }
        val total = process.totalUsage()
        val models = process.totalModelsUsed().map { it.name }.distinct().joinToString(",").ifBlank { null }
        return UsageSummary(total.promptTokens ?: 0, total.completionTokens ?: 0, reported, models)
    }

    private fun terminationReason(process: AgentProcess): String =
        (process.failureInfo as? EarlyTermination)?.reason ?: process.failureInfo?.toString() ?: "unknown reason"

    private fun kill(agentPlatform: AgentPlatform, process: AgentProcess) {
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
                EarlyTermination(agentProcess, true, "job deadline $deadline reached", this)
            } else null
    }
}

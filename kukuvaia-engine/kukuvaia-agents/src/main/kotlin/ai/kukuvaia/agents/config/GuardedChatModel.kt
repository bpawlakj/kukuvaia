package ai.kukuvaia.agents.config

import ai.kukuvaia.agents.daemon.DaemonEmbabelRuns
import ai.kukuvaia.provider.service.LlmProviderService.DaemonProviderPolicyException
import ai.kukuvaia.provider.transport.OutputTokenLimits
import ai.kukuvaia.security.ToolResultSanitizingAdvisor
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.NonRetryable
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.time.Instant

/**
 * The guards kukuvaia's ChatClients get from advisors, for Embabel's direct `ChatModel` path:
 *
 * - every prompt carries the "tool results are data" security boundary ([ToolResultSanitizingAdvisor.SAFETY_SUFFIX]);
 * - during a daemon run ([DaemonEmbabelRuns]) a Copilot-backed model is refused unless the job opted in,
 *   and a model with unknown provider metadata is refused too (fail closed, same rule as `LlmProviderService`);
 *   a call with no visible Embabel process while daemon runs are active is treated as daemon (fail closed);
 * - a daemon run's calls are refused once it is stopped, past its deadline or at its `token_budget`
 *   (counted here per call, so the cap holds inside a single action);
 * - calls and token usage are counted per role (`kukuvaia.embabel.llm.*`), like `ProviderAuditLog`.
 *
 * @param copilot resolved per call: `true`/`false` from the role's provider metadata, `null` when unknown
 */
private const val COPILOT_REASON =
    "resolves to a GitHub Copilot provider — Copilot is opt-in only for daemon work (set provider: copilot on the job)"

class GuardedChatModel(
    private val role: String,
    private val delegate: ChatModel,
    private val copilot: () -> Boolean?,
    private val runs: DaemonEmbabelRuns?,
    private val meterRegistry: MeterRegistry?,
) : ChatModel {

    /** Convenience for a fixed provider flag. */
    constructor(role: String, delegate: ChatModel, copilot: Boolean?, runs: DaemonEmbabelRuns?, meterRegistry: MeterRegistry?) :
        this(role, delegate, { copilot }, runs, meterRegistry)

    /** Result of the policy check: the run (if any) and whether the call counts as daemon work. */
    private class Scope(val policy: DaemonEmbabelRuns.RunPolicy?, val daemon: Boolean)

    override fun call(prompt: Prompt): ChatResponse {
        val scope = checkPolicy()
        val work = scope.policy?.lifetime?.register()
        try {
            val response = delegate.call(guard(prompt, scope))
            record(scope, response)
            return response
        } finally {
            work?.close()
        }
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        // Capture ownership on the Embabel thread, then enforce policy on every subscription.
        val captured = resolveScope()
        return Flux.defer {
            val scope = checkPolicy(captured)
            scope.policy?.unmetered?.incrementAndGet() // streamed usage is not reliably reported
            val work = scope.policy?.lifetime?.register()
            try {
                delegate.stream(guard(prompt, scope)).doOnNext { record(scope, it, streaming = true) }
                    .doFinally { work?.close() }
            } catch (e: Throwable) {
                work?.close()
                Flux.error(e)
            }
        }
    }

    override fun getOptions(): ChatOptions = delegate.options

    private fun resolveScope(): Scope {
        val process = AgentProcess.get()
        val policy = runs?.policyFor(process)
        // No Embabel process visible on this thread (e.g. a thread the agent started itself) while daemon
        // runs are active: we cannot tell whose call this is — treat it as daemon work (fail closed).
        val daemon = policy != null || (process == null && (runs?.active() ?: 0) > 0)
        return Scope(policy, daemon)
    }

    private fun checkPolicy(scope: Scope = resolveScope()): Scope {
        val policy = scope.policy
        if (!scope.daemon) return scope

        val job = policy?.jobName ?: "(unidentified daemon thread)"
        val how = "Embabel role '$role' of daemon job '$job'"
        if (policy != null) {
            when {
                policy.stopped -> refuse(policy, GuardRefusal(how, "the run has ended (killed, timed out or finished)"))
                Instant.now().isAfter(policy.deadline) -> refuse(policy, GuardRefusal(how, "job deadline passed"))
                policy.tokens.get() >= policy.tokenBudget ->
                    refuse(policy, GuardRefusal(how, "token_budget of ${policy.tokenBudget} reached"))
            }
        }
        val isCopilot = copilot()
        when {
            isCopilot == null -> refuse(policy, GuardRefusal(how, "no provider metadata — cannot verify it is not Copilot"))
            isCopilot && policy?.copilotAllowed != true -> refuse(policy, GuardRefusal(how, null))
        }
        policy?.calls?.incrementAndGet()
        return Scope(policy, true)
    }

    /**
     * Refusal raised inside an Embabel action. [NonRetryable] so Embabel does not retry the action with
     * backoff (a refused run would otherwise keep a platform thread busy until its retries are exhausted).
     */
    class GuardRefusal(how: String, reason: String?) :
        DaemonProviderPolicyException(how, reason ?: COPILOT_REASON), NonRetryable

    private fun refuse(policy: DaemonEmbabelRuns.RunPolicy?, e: DaemonProviderPolicyException): Nothing {
        policy?.let { if (it.refusal == null) it.refusal = e.message }
        meterRegistry?.counter("kukuvaia.embabel.llm.refused", "role", role)?.increment()
        throw e
    }

    private fun guard(prompt: Prompt, scope: Scope): Prompt {
        val secured = prompt.augmentSystemMessage(ToolResultSanitizingAdvisor.SAFETY_SUFFIX)
        val cap = scope.policy?.maxTokens ?: 0
        if (cap <= 0) return secured
        val options = prompt.options ?: delegate.options
        return Prompt(secured.instructions, OutputTokenLimits.cap(options, cap))
    }

    private fun record(scope: Scope, response: ChatResponse?, streaming: Boolean = false) {
        val usage = response?.metadata?.usage
        val total = (usage?.promptTokens ?: 0) + (usage?.completionTokens ?: 0)
        scope.policy?.let { policy ->
            if (total > 0) policy.tokens.addAndGet(total)
            if (!streaming && (total <= 0 || response?.metadata?.get<Boolean>(ai.kukuvaia.provider.transport.TransportUsage.COMPLETE) == false)) {
                policy.unmetered.incrementAndGet()
            }
        }
        val registry = meterRegistry ?: return
        if (!streaming) {
            registry.counter("kukuvaia.embabel.llm.calls", "role", role, "context", if (scope.daemon) "daemon" else "interactive").increment()
        }
        usage?.promptTokens?.let { registry.counter("kukuvaia.embabel.llm.tokens", "role", role, "type", "prompt").increment(it.toDouble()) }
        usage?.completionTokens?.let { registry.counter("kukuvaia.embabel.llm.tokens", "role", role, "type", "completion").increment(it.toDouble()) }
    }
}

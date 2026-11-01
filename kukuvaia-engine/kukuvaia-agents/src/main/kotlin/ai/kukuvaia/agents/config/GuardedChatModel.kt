package ai.kukuvaia.agents.config

import ai.kukuvaia.agents.daemon.DaemonEmbabelRuns
import ai.kukuvaia.provider.service.LlmProviderService.DaemonProviderPolicyException
import ai.kukuvaia.security.ToolResultSanitizingAdvisor
import com.embabel.agent.core.AgentProcess
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

/**
 * The guards kukuvaia's ChatClients get from advisors, for Embabel's direct `ChatModel` path:
 *
 * - every prompt carries the "tool results are data" security boundary ([ToolResultSanitizingAdvisor.SAFETY_SUFFIX]);
 * - during a daemon run ([DaemonEmbabelRuns]) a Copilot-backed model is refused unless the job opted in,
 *   and a model with unknown provider metadata is refused too (fail closed, same rule as `LlmProviderService`);
 * - calls and token usage are counted per role (`kukuvaia.embabel.llm.*`), like `ProviderAuditLog`.
 *
 * @param copilot `true`/`false` from the role's provider metadata, `null` when unknown
 */
class GuardedChatModel(
    private val role: String,
    private val delegate: ChatModel,
    private val copilot: Boolean?,
    private val runs: DaemonEmbabelRuns?,
    private val meterRegistry: MeterRegistry?,
) : ChatModel {

    override fun call(prompt: Prompt): ChatResponse {
        val daemon = checkPolicy()
        val response = delegate.call(guard(prompt))
        record(daemon, response)
        return response
    }

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        val daemon = checkPolicy()
        return delegate.stream(guard(prompt)).doOnNext { record(daemon, it) }
    }

    override fun getDefaultOptions(): ChatOptions = delegate.defaultOptions

    /** @return whether this call belongs to a daemon run */
    private fun checkPolicy(): Boolean {
        val policy = runs?.policyFor(AgentProcess.get()) ?: return false
        val how = "Embabel role '$role' of daemon job '${policy.jobName}'"
        when {
            copilot == null -> refuse(policy, DaemonProviderPolicyException(how, "no provider metadata — cannot verify it is not Copilot"))
            copilot && !policy.copilotAllowed -> refuse(policy, DaemonProviderPolicyException(how))
        }
        return true
    }

    private fun refuse(policy: DaemonEmbabelRuns.RunPolicy, e: DaemonProviderPolicyException): Nothing {
        policy.refusal = e.message
        meterRegistry?.counter("kukuvaia.embabel.llm.refused", "role", role)?.increment()
        throw e
    }

    private fun guard(prompt: Prompt): Prompt = prompt.augmentSystemMessage(ToolResultSanitizingAdvisor.SAFETY_SUFFIX)

    private fun record(daemon: Boolean, response: ChatResponse?) {
        val registry = meterRegistry ?: return
        val context = if (daemon) "daemon" else "interactive"
        registry.counter("kukuvaia.embabel.llm.calls", "role", role, "context", context).increment()
        val usage = response?.metadata?.usage ?: return
        usage.promptTokens?.let { registry.counter("kukuvaia.embabel.llm.tokens", "role", role, "type", "prompt").increment(it.toDouble()) }
        usage.completionTokens?.let { registry.counter("kukuvaia.embabel.llm.tokens", "role", role, "type", "completion").increment(it.toDouble()) }
    }
}

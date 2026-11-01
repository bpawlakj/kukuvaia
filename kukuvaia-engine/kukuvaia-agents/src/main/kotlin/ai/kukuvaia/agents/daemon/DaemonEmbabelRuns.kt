package ai.kukuvaia.agents.daemon

import com.embabel.agent.core.AgentProcess
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Embabel agent processes currently running as daemon jobs.
 *
 * Embabel calls `ChatModel` directly (no ChatClient advisors), so [ai.kukuvaia.agents.config.GuardedChatModel]
 * looks up the current process (Embabel's thread-local [AgentProcess.get]) here to apply the daemon
 * provider policy. Child processes inherit their parent's policy.
 */
@Component
class DaemonEmbabelRuns {

    class RunPolicy(val jobName: String, val copilotAllowed: Boolean) {
        /** Set by the guard when it refused a model — the executor then stops the process (Embabel would retry). */
        @Volatile var refusal: String? = null
    }

    private val runs = ConcurrentHashMap<String, RunPolicy>()

    fun register(processId: String, policy: RunPolicy) {
        runs[processId] = policy
    }

    fun unregister(processId: String) {
        runs.remove(processId)
    }

    fun policyFor(process: AgentProcess?): RunPolicy? {
        if (process == null) return null
        runs[process.id]?.let { return it }
        return process.parentId?.let { runs[it] }
    }

    fun active(): Int = runs.size
}

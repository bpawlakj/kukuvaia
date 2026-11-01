package ai.kukuvaia.agents.daemon

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embabel agent processes currently running as daemon jobs, and the per-run accounting the guard needs.
 *
 * Embabel calls `ChatModel` directly (no ChatClient advisors), so [ai.kukuvaia.agents.config.GuardedChatModel]
 * resolves the current process (Embabel's thread-local [AgentProcess.get]) here. Child processes at any
 * depth inherit the root run's policy. A run stays registered until its process has really ended
 * (not just been killed), so late calls of a killed process are still recognised — and refused.
 */
@Component
class DaemonEmbabelRuns(private val platform: ObjectProvider<AgentPlatform>) {

    class RunPolicy(
        val jobName: String,
        val copilotAllowed: Boolean,
        val deadline: Instant = Instant.MAX,
        val tokenBudget: Int = Int.MAX_VALUE,
    ) {
        /** Set by the guard when it refused a model — the executor then stops the process (Embabel would retry). */
        @Volatile var refusal: String? = null
        /** Set by the executor once the run is over (completed, killed, timed out); later calls are refused. */
        @Volatile var stopped: Boolean = false
        /** Model calls and tokens seen by the guard (independent of what Embabel records). */
        val calls = AtomicInteger()
        val tokens = AtomicInteger()
        /** Calls whose response carried no usage — the run's usage then counts as unreported. */
        val unmetered = AtomicInteger()
    }

    private val runs = ConcurrentHashMap<String, RunPolicy>()

    fun register(processId: String, policy: RunPolicy) {
        runs[processId] = policy
    }

    fun unregister(processId: String) {
        runs.remove(processId)
    }

    /** Policy of the process or of its closest registered ancestor. */
    fun policyFor(process: AgentProcess?): RunPolicy? {
        var current = process
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            runs[current.id]?.let { return it }
            val parentId = current.parentId ?: return null
            runs[parentId]?.let { return it }
            current = platform.ifAvailable?.getAgentProcess(parentId)
        }
        return null
    }

    fun active(): Int = runs.size

    private companion object {
        const val MAX_DEPTH = 16
    }
}

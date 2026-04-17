package ai.kukuvaia.agents

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.domain.io.UserInput

/**
 * Minimal deterministic agent to verify Embabel framework wiring.
 * No LLM calls — pure Kotlin logic.
 */

data class PingResult(
    val message: String,
    val echo: String,
)

@Agent(description = "Simple ping agent that echoes user input — verifies Embabel wiring")
class PingAgent {

    @AchievesGoal(description = "Echo the user input back with a greeting")
    @Action
    fun ping(userInput: UserInput): PingResult =
        PingResult(
            message = "pong from Embabel!",
            echo = userInput.content,
        )
}

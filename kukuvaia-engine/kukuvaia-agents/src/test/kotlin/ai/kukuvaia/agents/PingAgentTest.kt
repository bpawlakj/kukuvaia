package ai.kukuvaia.agents

import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

internal class PingAgentTest {

    @Test
    fun ping_echoesUserInput() {
        val agent = PingAgent()
        val input = UserInput("hello kukuvaia", Instant.now())

        val result = agent.ping(input)

        assertEquals("pong from Embabel!", result.message)
        assertEquals("hello kukuvaia", result.echo)
    }
}

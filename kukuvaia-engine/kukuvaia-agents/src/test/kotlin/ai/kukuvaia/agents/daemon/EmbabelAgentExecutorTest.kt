package ai.kukuvaia.agents.daemon

import ai.kukuvaia.agent.daemon.def.DaemonJobRegistry
import ai.kukuvaia.agent.daemon.exec.ExecutionFailedException
import ai.kukuvaia.agent.daemon.exec.JobInvocation
import ai.kukuvaia.agent.daemon.exec.JobLimits
import ai.kukuvaia.agents.PingAgent
import ai.kukuvaia.agents.config.GuardedChatModel
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.Usage
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.common.ai.model.LlmMetadata
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.support.StaticListableBeanFactory
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.Map.entry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.embabel.agent.core.Agent as AgentDefinition

data class Step1(val n: Int)
data class Step2(val n: Int)
data class Step3(val n: Int)
data class Slept(val ok: Boolean)
data class Answer(val text: String)

private fun spend(prompt: Int, completion: Int) {
    AgentProcess.get()!!.recordLlmInvocation(
        LlmInvocation(LlmMetadata.create("stub-model", "test"), Usage(prompt, completion, null), "test", Instant.now(), Duration.ZERO)
    )
}

@Agent(description = "Three steps, 1000 tokens each")
class TokenHungryAgent {
    @Action fun one(input: UserInput): Step1 { spend(600, 400); return Step1(1) }
    @Action fun two(s: Step1): Step2 { spend(600, 400); return Step2(2) }
    @AchievesGoal(description = "three") @Action fun three(s: Step2): Step3 { spend(600, 400); return Step3(3) }
}

@Agent(description = "Never finishes in time")
class SlowAgent {
    @AchievesGoal(description = "sleep") @Action
    fun sleep(input: UserInput): Slept { Thread.sleep(10_000); return Slept(true) }
}

/** Calls a Copilot-backed model the way Embabel does (ChatModel directly, inside an action). */
@Agent(description = "Uses a Copilot model")
class CopilotAgent {
    companion object { lateinit var model: ChatModel }

    @AchievesGoal(description = "answer") @Action
    fun answer(input: UserInput): Answer = Answer(model.call(Prompt(input.content)).result!!.output.text ?: "")
}

@DisplayName("EmbabelAgentExecutor — kind: embabel under daemon limits and provider policy")
class EmbabelAgentExecutorTest {

    private lateinit var platform: AgentPlatform
    private val runs = DaemonEmbabelRuns()
    private lateinit var executor: EmbabelAgentExecutor

    @BeforeEach
    fun setUp() {
        platform = IntegrationTestUtils.dummyAgentPlatform()
        val reader = AgentMetadataReader()
        listOf(PingAgent(), TokenHungryAgent(), SlowAgent(), CopilotAgent())
            .forEach { platform.deploy(reader.createAgentMetadata(it) as AgentDefinition) }
        val beans = StaticListableBeanFactory()
        beans.addBean("platform", platform)
        beans.addBean("mapper", JsonMapper.builder().build())
        executor = EmbabelAgentExecutor(beans.getBeanProvider(AgentPlatform::class.java), runs,
            beans.getBeanProvider(tools.jackson.databind.ObjectMapper::class.java))
    }

    private fun invocation(target: String, prompt: String = "hello daemon", provider: String? = null, timeout: Duration = Duration.ofSeconds(10)) =
        JobInvocation(1, "test-job", target, prompt, mapOf(), provider, Instant.now().plus(timeout))

    private fun limits(tokenBudget: Int = 20_000, maxActions: Int = 20) =
        JobLimits(Duration.ofSeconds(10), 0, 0, maxActions, tokenBudget)

    @Test
    @DisplayName("YAML job kind: embabel, target: PingAgent is accepted and completes with the agent's result")
    fun pingAgentViaYaml() {
        val registry = DaemonJobRegistry(listOf(executor), listOf(entry("ping.yaml",
            "name: embabel-ping\nkind: embabel\ntarget: PingAgent\ninput: { fields: [msg] }\nprompt: \"ping {{fields.msg}}\"\n")))
        assertEquals(listOf("embabel-ping"), registry.all().map { it.name() }, registry.rejected().toString())

        val outcome = executor.execute(invocation("PingAgent", "ping hi"), limits())

        assertTrue(outcome.text().contains("pong from Embabel!"), outcome.text())
        assertEquals("ping hi", outcome.output()!!.get("echo").asString())
        assertTrue(outcome.usageReported())
        assertEquals(0, outcome.totalTokens())
        assertEquals(0, runs.active())
    }

    @Test
    @DisplayName("validation: unknown agent and non-Copilot provider override rejected; explicit copilot opt-in accepted")
    fun validation() {
        val registry = DaemonJobRegistry(listOf(executor), listOf(
            entry("a.yaml", "name: ghost\nkind: embabel\ntarget: NoSuchAgent\n"),
            entry("b.yaml", "name: routed\nkind: embabel\ntarget: PingAgent\nprovider: smartgate\n"),
            entry("c.yaml", "name: opted-in\nkind: embabel\ntarget: PingAgent\nprovider: copilot\n")))

        assertEquals(listOf("opted-in"), registry.all().map { it.name() })
        val rejected = registry.rejected().joinToString("\n")
        assertTrue(rejected.contains("no Embabel agent named 'NoSuchAgent'"), rejected)
        assertTrue(rejected.contains("provider 'smartgate' is not supported"), rejected)
    }

    @Test
    @DisplayName("token_budget becomes Embabel's Budget: run terminated early, real usage reported for charging")
    fun tokenBudgetTerminatesEarly() {
        val e = assertFailsWith<ExecutionFailedException> {
            executor.execute(invocation("TokenHungryAgent"), limits(tokenBudget = 1500))
        }

        assertTrue(e.message!!.contains("terminated early"), e.message)
        assertTrue(e.usageReported())
        assertTrue(e.totalTokens() in 1500..2000, "charged ${e.totalTokens()}")
        assertEquals(false, e.timedOut())
    }

    @Test
    @DisplayName("deadline: process killed, reported as timed out, executor returns promptly")
    fun deadlineKills() {
        val start = System.nanoTime()
        val e = assertFailsWith<ExecutionFailedException> {
            executor.execute(invocation("SlowAgent", timeout = Duration.ofMillis(500)), limits())
        }
        val elapsed = Duration.ofNanos(System.nanoTime() - start)

        assertTrue(e.timedOut())
        assertTrue(elapsed < Duration.ofSeconds(3), "took $elapsed")
        assertEquals(0, runs.active())
    }

    @Test
    @DisplayName("daemon run on a Copilot-backed model is refused unless the job opted in with provider: copilot")
    fun copilotRefused() {
        val stub = ChatModel { ChatResponse(listOf(Generation(AssistantMessage("copilot says hi")))) }
        CopilotAgent.model = GuardedChatModel("worker", stub, copilot = true, runs = runs, meterRegistry = null)

        val refused = assertFailsWith<ExecutionFailedException> { executor.execute(invocation("CopilotAgent"), limits()) }
        assertTrue(refused.message!!.contains("Copilot"), refused.message)

        val allowed = executor.execute(invocation("CopilotAgent", provider = "copilot"), limits())
        assertTrue(allowed.text().contains("copilot says hi"), allowed.text())
    }
}

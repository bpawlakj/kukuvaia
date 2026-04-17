package ai.kukuvaia.agents.research

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("ResearchAgent — GOAP multi-model research")
@ExtendWith(MockitoExtension::class)
class ResearchAgentTest {

    @Mock
    private lateinit var operationContext: OperationContext

    @Mock
    private lateinit var ai: Ai

    @Mock
    private lateinit var cheapPromptRunner: PromptRunner

    @Mock
    private lateinit var bestPromptRunner: PromptRunner

    private lateinit var agent: ResearchAgent

    @BeforeEach
    fun setUp() {
        agent = ResearchAgent()
        whenever(operationContext.ai()).thenReturn(ai)
    }

    @Test
    @DisplayName("quickScan — uses cheapest role and returns InitialFindings")
    fun quickScan_usesCheapestRole() {
        whenever(ai.withLlmByRole("cheapest")).thenReturn(cheapPromptRunner)
        whenever(cheapPromptRunner.generateText(any<String>())).thenReturn(
            """
            Summary of the topic.
            - Key point one
            - Key point two
            - Key point three
            """.trimIndent()
        )

        val query = ResearchQuery(topic = "Kotlin coroutines", depth = "standard")
        val result = agent.quickScan(query, operationContext)

        assertEquals("Kotlin coroutines", result.topic)
        assertTrue(result.summary.isNotBlank())
        assertTrue(result.keyPoints.isNotEmpty())
        verify(ai).withLlmByRole("cheapest")
    }

    @Test
    @DisplayName("deepAnalysis — uses best role and returns AnalysisResult")
    fun deepAnalysis_usesBestRole() {
        whenever(ai.withLlmByRole("best")).thenReturn(bestPromptRunner)
        whenever(bestPromptRunner.generateText(any<String>())).thenReturn(
            """
            Detailed analysis of Kotlin coroutines.
            1. Coroutines provide lightweight concurrency
            2. They integrate well with Spring WebFlux
            I recommend using structured concurrency for production code.
            """.trimIndent()
        )

        val findings = InitialFindings(
            topic = "Kotlin coroutines",
            summary = "Coroutines are lightweight threads",
            keyPoints = listOf("Lightweight", "Structured concurrency", "Spring integration"),
        )
        val result = agent.deepAnalysis(findings, operationContext)

        assertEquals("Kotlin coroutines", result.topic)
        assertTrue(result.analysis.isNotBlank())
        assertEquals(listOf("Lightweight", "Structured concurrency", "Spring integration"), result.findings)
        assertTrue(result.recommendations.isNotEmpty())
        verify(ai).withLlmByRole("best")
    }

    @Test
    @DisplayName("quickScan — extracts bullet points from response")
    fun quickScan_extractsBulletPoints() {
        whenever(ai.withLlmByRole("cheapest")).thenReturn(cheapPromptRunner)
        whenever(cheapPromptRunner.generateText(any<String>())).thenReturn(
            """
            Spring Boot is popular.
            - Auto-configuration
            - Embedded server
            • Starter dependencies
            """.trimIndent()
        )

        val result = agent.quickScan(ResearchQuery("Spring Boot"), operationContext)

        assertEquals(3, result.keyPoints.size)
        assertTrue(result.keyPoints.contains("Auto-configuration"))
        assertTrue(result.keyPoints.contains("Embedded server"))
        assertTrue(result.keyPoints.contains("Starter dependencies"))
    }

    @Test
    @DisplayName("quickScan — no bullets → falls back to truncated text")
    fun quickScan_noBullets_fallsBack() {
        whenever(ai.withLlmByRole("cheapest")).thenReturn(cheapPromptRunner)
        whenever(cheapPromptRunner.generateText(any<String>())).thenReturn(
            "Just a plain paragraph without any bullet points."
        )

        val result = agent.quickScan(ResearchQuery("test"), operationContext)

        assertEquals(1, result.keyPoints.size)
        assertTrue(result.keyPoints[0].startsWith("Just a plain"))
    }
}

@DisplayName("ResearchModels — data class validation")
class ResearchModelsTest {

    @Test
    @DisplayName("ResearchQuery — default depth is standard")
    fun researchQuery_defaultDepth() {
        val query = ResearchQuery(topic = "test")
        assertEquals("standard", query.depth)
    }

    @Test
    @DisplayName("InitialFindings — holds topic and key points")
    fun initialFindings_holdsData() {
        val findings = InitialFindings("topic", "summary", listOf("a", "b"))
        assertEquals("topic", findings.topic)
        assertEquals(2, findings.keyPoints.size)
    }

    @Test
    @DisplayName("AnalysisResult — default confidence is 0.0")
    fun analysisResult_defaultConfidence() {
        val result = AnalysisResult("topic", "analysis", listOf(), listOf())
        assertEquals(0.0, result.confidence)
    }
}

package ai.kukuvaia.agents.research

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext

/**
 * Multi-step research agent demonstrating GOAP planning with per-action model tiers.
 *
 * GOAP discovers the action sequence automatically:
 *   ResearchQuery → quickScan(cheapest/Haiku) → InitialFindings
 *                 → deepAnalysis(best/Opus)    → AnalysisResult
 *
 * The GOAP planner uses A* to find the optimal path from ResearchQuery to AnalysisResult.
 * Each action uses a different model tier for cost optimization:
 * - quickScan: cheap/fast model (Haiku) — extracts key points, low reasoning required
 * - deepAnalysis: powerful model (Opus) — deep reasoning, synthesis, recommendations
 */
@Agent(description = "Research agent with tiered model routing — cheap scan, deep analysis")
class ResearchAgent {

    /**
     * Quick topic scan using the cheapest available model.
     * Extracts key points and summary — low reasoning, high throughput.
     */
    @Action(description = "Quick topic scan using economy model")
    fun quickScan(query: ResearchQuery, ctx: OperationContext): InitialFindings {
        val response = ctx.ai()
            .withLlmByRole("cheapest")
            .generateText("""
                Scan the following topic and provide:
                1. A brief summary (2-3 sentences)
                2. Key points (bullet list)

                Topic: ${query.topic}
                Depth: ${query.depth}
            """.trimIndent())

        return InitialFindings(
            topic = query.topic,
            summary = response,
            keyPoints = extractKeyPoints(response),
        )
    }

    /**
     * Deep analysis using the most powerful available model.
     * Synthesizes findings, identifies patterns, produces recommendations.
     */
    @AchievesGoal(description = "Deep research analysis compiled with recommendations")
    @Action(description = "Deep analysis using premium model")
    fun deepAnalysis(findings: InitialFindings, ctx: OperationContext): AnalysisResult {
        val response = ctx.ai()
            .withLlmByRole("best")
            .generateText("""
                Based on these initial findings, provide a deep analysis:

                Topic: ${findings.topic}
                Summary: ${findings.summary}
                Key Points: ${findings.keyPoints.joinToString("\n- ", prefix = "- ")}

                Include:
                1. Detailed analysis of each key point
                2. Patterns and relationships identified
                3. Actionable recommendations
            """.trimIndent())

        return AnalysisResult(
            topic = findings.topic,
            analysis = response,
            findings = findings.keyPoints,
            recommendations = extractRecommendations(response),
        )
    }

    private fun extractKeyPoints(text: String): List<String> =
        text.lines()
            .filter { it.trimStart().startsWith("-") || it.trimStart().startsWith("•") }
            .map { it.trimStart('-', '•', ' ') }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text.take(200)) }

    private fun extractRecommendations(text: String): List<String> =
        text.lines()
            .filter { it.contains("recommend", ignoreCase = true) || it.trimStart().matches(Regex("^\\d+\\..*")) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("See analysis for details") }
}

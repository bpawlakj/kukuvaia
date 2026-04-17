package ai.kukuvaia.agents.research

/**
 * GOAP state types for the ResearchAgent.
 * Each data class represents a typed state on the blackboard.
 * GOAP planner discovers: ResearchQuery → InitialFindings → AnalysisResult
 */

/** Input: what to research. */
data class ResearchQuery(
    val topic: String,
    val depth: String = "standard", // "quick", "standard", "deep"
)

/** Intermediate: quick scan findings from cheap model. */
data class InitialFindings(
    val topic: String,
    val summary: String,
    val keyPoints: List<String>,
)

/** Output: deep analysis from powerful model. */
data class AnalysisResult(
    val topic: String,
    val analysis: String,
    val findings: List<String>,
    val recommendations: List<String>,
    val confidence: Double = 0.0,
)

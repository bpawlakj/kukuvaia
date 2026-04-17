# P10: Embabel GOAP Agents — Real Agent Implementations

**Created**: 2026-04-10
**Status**: Draft
**Module**: kukuvaia-agents (Kotlin), kukuvaia-core (Java integration)
**Depends on**: Embabel 0.3.4 framework, EmbabelModelBridgeConfig, SubAgentFactory, ToolRegistryConfig

---

## Problem

The kukuvaia-agents module has Embabel 0.3.4 wired with a fully functional model bridge (`KukuvaiaModelProvider`) and one trivial test agent (`PingAgent`) that verifies framework wiring with zero LLM calls. The GOAP (Goal-Oriented Action Planning) infrastructure is available but unused.

Without real GOAP agents, Kukuvaia cannot demonstrate:
- **Multi-step autonomous task execution** with goal decomposition
- **Per-action model selection** — cheap models for simple steps, powerful models for complex analysis
- **Automatic planning** — Embabel's GOAP planner selects the optimal action sequence to achieve a goal
- **Agent composability** — agents calling other agents or sharing intermediate results
- **The value of Embabel** over simple sequential tool calling via Spring AI's ToolCallAdvisor

This plan implements three concrete, useful agents that exercise real GOAP planning and demonstrate the architectural benefits.

---

## Current State

### Embabel wiring (operational)

| Component | Status | Location |
|-----------|--------|----------|
| `PingAgent` | Working | `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/PingAgent.kt` |
| `EmbabelModelBridgeConfig` | Working | `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/config/EmbabelModelBridgeConfig.kt` |
| `KukuvaiaModelProvider` | Working | Same file — runtime-updatable ModelProvider |
| `embabel-agent-starter:0.3.4` | Dependency | `kukuvaia-agents/build.gradle` |
| `embabel-agent-test:0.3.4` | Test dependency | Same |
| `PingAgentTest` | Passing | `kukuvaia-agents/src/test/kotlin/ai/kukuvaia/agents/PingAgentTest.kt` |

### What Embabel provides

```
@Agent annotation        → Registers agent with GOAP planner
@Action annotation       → Defines an action that transforms input → output
@AchievesGoal annotation → Marks the final action that produces the goal result
ProcessContext            → Provides ai() for per-action LLM access, tool calling
GOAP Planner              → Given a goal type + available actions, finds optimal path
```

GOAP planning works by:
1. You define a **goal type** (e.g., `ResearchReport`)
2. You define **actions** with typed inputs and outputs (e.g., `UserInput → SearchResults → Analysis → ResearchReport`)
3. The GOAP planner finds the action sequence that transforms the input into the goal

### What SubAgentFactory provides (Java, kukuvaia-core)

`SubAgentFactory` creates isolated `ChatClient` instances for specialist sub-agents with:
- Separate ChatClient per agent (no shared conversation state)
- Tool filtering (only allowed tools per specialist)
- Depth guard (max depth = 1, no recursive spawning)
- Timeout enforcement
- Tier-based model resolution via DB roles

**Key design decision**: Embabel agents and SubAgentFactory serve different purposes:
- **Embabel agents**: Multi-step GOAP planning with typed data flow, Kotlin DSL, per-action model selection
- **SubAgentFactory**: One-shot specialist delegation from the main ChatClient, Java-based, tool-filtered

Both patterns coexist. Embabel agents can internally use SubAgentFactory for delegation, or use `ProcessContext.ai()` for direct LLM calls.

---

## Architecture

### Agent overview

```
┌──────────────────────────────────────────────────────────────┐
│                    Embabel Agent Platform                      │
│                                                                │
│  ┌───────────────┐  ┌──────────────────┐  ┌────────────────┐ │
│  │ ResearchAgent  │  │ ValidationAgent  │  │ CodeReviewAgent│ │
│  │               │  │                  │  │                │ │
│  │ Goal:          │  │ Goal:            │  │ Goal:          │ │
│  │ ResearchReport │  │ ValidationReport │  │ ReviewReport   │ │
│  │               │  │                  │  │                │ │
│  │ Actions:       │  │ Actions:         │  │ Actions:       │ │
│  │ 1. search     │  │ 1. loadDoc       │  │ 1. parseDiff   │ │
│  │    (cheapest)  │  │    (cheapest)    │  │    (cheapest)  │ │
│  │ 2. synthesize  │  │ 2. validate      │  │ 2. analyze     │ │
│  │    (cheapest)  │  │    (cheapest)    │  │    (best)      │ │
│  │ 3. analyze     │  │ 3. suggest       │  │ 3. summarize   │ │
│  │    (best)      │  │    (best)        │  │    (cheapest)  │ │
│  └───────────────┘  └──────────────────┘  └────────────────┘ │
│                                                                │
│  KukuvaiaModelProvider                                         │
│    cheapest → Haiku | best → Opus | default → Sonnet          │
│    (resolved from kukuvaia.model_roles DB table)               │
└──────────────────────────────────────────────────────────────┘
```

### GOAP planning for ResearchAgent

```
UserInput("What are the best practices for rate limiting in Spring Boot?")
  │
  ▼  GOAP planner finds: UserInput → SearchResults → SynthesizedFindings → ResearchReport
  │
  ├── Action: search (cheapest/Haiku)
  │     Input: UserInput
  │     Output: SearchResults (list of relevant items from tools)
  │     LLM: Extract search queries, call search tools, collect results
  │
  ├── Action: synthesize (cheapest/Haiku)
  │     Input: SearchResults
  │     Output: SynthesizedFindings (deduplicated, organized findings)
  │     LLM: Merge overlapping results, organize by theme
  │
  └── Action: analyze (best/Opus)
        Input: SynthesizedFindings
        Output: ResearchReport (final answer with sources)
        LLM: Deep analysis, identify gaps, provide recommendations
        @AchievesGoal
```

---

## Implementation

### Step 1: Shared agent models

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/model/AgentModels.kt`

```kotlin
package ai.kukuvaia.agents.model

/**
 * Shared data types for agent communication.
 * Embabel's GOAP planner uses these types to discover action chains.
 */

data class SearchResults(
    val query: String,
    val results: List<SearchResult>,
    val toolsUsed: List<String>,
)

data class SearchResult(
    val title: String,
    val content: String,
    val source: String,
    val relevanceScore: Double = 0.0,
)

data class SynthesizedFindings(
    val topic: String,
    val themes: List<Theme>,
    val sourceCount: Int,
)

data class Theme(
    val name: String,
    val summary: String,
    val sources: List<String>,
)

data class ResearchReport(
    val question: String,
    val answer: String,
    val themes: List<Theme>,
    val gaps: List<String>,
    val sources: List<String>,
)
```

### Step 2: ResearchAgent

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/research/ResearchAgent.kt`

```kotlin
package ai.kukuvaia.agents.research

import ai.kukuvaia.agents.model.*
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput

/**
 * Research agent: given a question, searches available tools,
 * synthesizes findings, and produces a report with sources.
 *
 * GOAP plan: UserInput → SearchResults → SynthesizedFindings → ResearchReport
 *
 * Model strategy:
 * - search + synthesize: cheapest (Haiku) — high-volume, low-complexity
 * - analyze: best (Opus) — deep reasoning, recommendation generation
 */
@Agent(description = "Research agent that searches, synthesizes, and analyzes information to answer questions")
class ResearchAgent {

    /**
     * Step 1: Generate search queries and execute them via available tools.
     * Uses cheapest model — query generation is straightforward.
     */
    @Action(description = "Search for information relevant to the user's question")
    fun search(userInput: UserInput, ctx: OperationContext): SearchResults {
        val searchPrompt = """
            Given this research question: "${userInput.content}"

            Generate 2-3 specific search queries to find relevant information.
            For each query, call the appropriate search tool (search_memories, search_items, etc.).
            Collect all results and return them as structured data.

            Return JSON with fields: query, results (array of {title, content, source}), toolsUsed
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("cheapest")
            .creating(SearchResults::class.java)
            .create(searchPrompt)
    }

    /**
     * Step 2: Synthesize search results into organized findings.
     * Uses cheapest model — deduplication and organization are mechanical.
     */
    @Action(description = "Synthesize search results into organized themes")
    fun synthesize(results: SearchResults, ctx: OperationContext): SynthesizedFindings {
        val synthesizePrompt = """
            Given these search results for "${results.query}":

            ${results.results.joinToString("\n") { "- [${it.source}] ${it.title}: ${it.content}" }}

            Organize into themes:
            1. Deduplicate overlapping information
            2. Group by theme/topic
            3. Note source attribution for each theme

            Return JSON with fields: topic, themes (array of {name, summary, sources}), sourceCount
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("cheapest")
            .creating(SynthesizedFindings::class.java)
            .create(synthesizePrompt)
    }

    /**
     * Step 3: Deep analysis — produce final research report.
     * Uses best model — requires reasoning, gap identification, recommendations.
     */
    @AchievesGoal(description = "Produce a comprehensive research report with analysis and recommendations")
    @Action(description = "Analyze synthesized findings and produce a research report")
    fun analyze(findings: SynthesizedFindings, ctx: OperationContext): ResearchReport {
        val analyzePrompt = """
            Produce a comprehensive research report for: "${findings.topic}"

            Organized findings:
            ${findings.themes.joinToString("\n") { "## ${it.name}\n${it.summary}\nSources: ${it.sources.joinToString()}" }}

            Requirements:
            1. Provide a clear, well-structured answer
            2. Identify knowledge gaps — what information is missing?
            3. Provide practical recommendations
            4. Cite sources for all claims

            Return JSON with fields: question, answer, themes, gaps (array of strings), sources (array of strings)
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("best")
            .creating(ResearchReport::class.java)
            .create(analyzePrompt)
    }
}
```

### Step 3: ValidationAgent

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/validation/ValidationModels.kt`

```kotlin
package ai.kukuvaia.agents.validation

data class DocumentReference(
    val documentId: String,
    val documentType: String,
    val content: String,
)

data class ValidationIssue(
    val ruleId: String,
    val severity: String,      // ERROR, WARNING, INFO
    val location: String,
    val message: String,
    val suggestion: String?,
)

data class ValidationFindings(
    val documentId: String,
    val issues: List<ValidationIssue>,
    val passedRules: Int,
    val failedRules: Int,
)

data class ValidationReport(
    val documentId: String,
    val summary: String,
    val issues: List<ValidationIssue>,
    val fixes: List<SuggestedFix>,
    val overallStatus: String,   // PASS, WARN, FAIL
)

data class SuggestedFix(
    val issueId: String,
    val description: String,
    val before: String,
    val after: String,
    val confidence: Double,
)
```

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/validation/ValidationAgent.kt`

```kotlin
package ai.kukuvaia.agents.validation

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput

/**
 * Validation agent: loads a document, runs validation rules, and suggests fixes.
 *
 * GOAP plan: UserInput → DocumentReference → ValidationFindings → ValidationReport
 *
 * Model strategy:
 * - loadDocument + validate: cheapest — rule application is deterministic
 * - suggestFixes: best — generating correct fixes requires deep understanding
 */
@Agent(description = "Validation agent that checks documents against rules and suggests fixes")
class ValidationAgent {

    /**
     * Step 1: Parse user request and load the target document.
     * Uses cheapest model — straightforward extraction.
     */
    @Action(description = "Parse validation request and load the target document")
    fun loadDocument(userInput: UserInput, ctx: OperationContext): DocumentReference {
        val prompt = """
            Parse this validation request: "${userInput.content}"

            Extract:
            1. The document identifier (ID, name, or path)
            2. The document type (outline, section, content_item, or general)

            Use the appropriate tool to load the document content:
            - For outlines: get_outline_raw
            - For sections: get_sections
            - For content items: get_content_items
            - For general: read_document

            Return JSON with fields: documentId, documentType, content
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("cheapest")
            .creating(DocumentReference::class.java)
            .create(prompt)
    }

    /**
     * Step 2: Run validation rules against the document.
     * Uses cheapest model — rule checking is structured and repetitive.
     */
    @Action(description = "Run validation rules against the loaded document")
    fun validate(document: DocumentReference, ctx: OperationContext): ValidationFindings {
        val prompt = """
            Validate this ${document.documentType} document (ID: ${document.documentId}):

            ${document.content.take(8000)}

            Check against these rules:
            1. STRUCTURE: proper heading hierarchy, required sections present
            2. FORMATTING: consistent style, no broken references, valid links
            3. CONSISTENCY: terminology consistency, no contradictions
            4. COMPLETENESS: no empty sections, no TODO placeholders left
            5. QUALITY: spelling, grammar, clarity of language

            For each issue found, provide:
            - Rule ID (e.g., "STRUCTURE-001")
            - Severity: ERROR (must fix), WARNING (should fix), INFO (suggestion)
            - Location (section/line reference)
            - Description
            - Suggestion for fix

            If applicable, also call run_validation tool for automated checks.

            Return JSON with fields: documentId, issues (array), passedRules (int), failedRules (int)
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("cheapest")
            .creating(ValidationFindings::class.java)
            .create(prompt)
    }

    /**
     * Step 3: Generate fix suggestions for found issues.
     * Uses best model — generating correct fixes requires deep understanding.
     */
    @AchievesGoal(description = "Produce a validation report with fix suggestions")
    @Action(description = "Suggest fixes for validation issues found in the document")
    fun suggestFixes(findings: ValidationFindings, ctx: OperationContext): ValidationReport {
        val issuesSummary = findings.issues.joinToString("\n") {
            "[${it.severity}] ${it.ruleId} at ${it.location}: ${it.message}"
        }

        val prompt = """
            Generate fix suggestions for these validation issues in document ${findings.documentId}:

            $issuesSummary

            For each ERROR and WARNING issue:
            1. Provide a concrete fix (before/after)
            2. Explain why the fix is correct
            3. Rate your confidence (0.0-1.0) in the fix

            Also provide an overall summary and status:
            - PASS: no errors or warnings
            - WARN: only warnings, no errors
            - FAIL: has errors

            Return JSON with fields: documentId, summary, issues, fixes (array of {issueId, description, before, after, confidence}), overallStatus
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("best")
            .creating(ValidationReport::class.java)
            .create(prompt)
    }
}
```

### Step 4: CodeReviewAgent

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/review/ReviewModels.kt`

```kotlin
package ai.kukuvaia.agents.review

data class ParsedDiff(
    val files: List<FileChange>,
    val totalAdditions: Int,
    val totalDeletions: Int,
    val languages: List<String>,
)

data class FileChange(
    val path: String,
    val language: String,
    val additions: Int,
    val deletions: Int,
    val hunks: List<String>,
)

data class ReviewComment(
    val file: String,
    val line: Int?,
    val severity: String,     // CRITICAL, MAJOR, MINOR, SUGGESTION
    val category: String,     // bug, security, performance, style, design
    val message: String,
    val suggestion: String?,
)

data class ReviewAnalysis(
    val comments: List<ReviewComment>,
    val criticalCount: Int,
    val majorCount: Int,
    val patternsDetected: List<String>,
)

data class ReviewReport(
    val summary: String,
    val verdict: String,       // APPROVE, REQUEST_CHANGES, COMMENT
    val comments: List<ReviewComment>,
    val highlights: List<String>,
    val suggestions: List<String>,
)
```

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/review/CodeReviewAgent.kt`

```kotlin
package ai.kukuvaia.agents.review

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput

/**
 * Code review agent: parses a diff, analyzes changes, and produces review comments.
 *
 * GOAP plan: UserInput → ParsedDiff → ReviewAnalysis → ReviewReport
 *
 * Model strategy:
 * - parseDiff: cheapest — mechanical parsing
 * - analyze: best — security, logic, design analysis requires deep reasoning
 * - summarize: cheapest — formatting the report is straightforward
 */
@Agent(description = "Code review agent that analyzes diffs and provides review comments with severity ratings")
class CodeReviewAgent {

    /**
     * Step 1: Parse the diff into structured file changes.
     * Uses cheapest model — diff parsing is mechanical.
     */
    @Action(description = "Parse a code diff into structured file changes")
    fun parseDiff(userInput: UserInput, ctx: OperationContext): ParsedDiff {
        val prompt = """
            Parse this code diff (or description of changes):

            ${userInput.content.take(10000)}

            Extract:
            1. List of files changed with path, language, additions/deletions
            2. Code hunks for each file
            3. Total additions and deletions
            4. Languages involved

            If the input is a description rather than a raw diff, use any available
            git_status or git_diff tools to retrieve the actual changes.

            Return JSON with fields: files (array of {path, language, additions, deletions, hunks}), totalAdditions, totalDeletions, languages
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("cheapest")
            .creating(ParsedDiff::class.java)
            .create(prompt)
    }

    /**
     * Step 2: Deep analysis of each file change.
     * Uses best model — security, logic, design analysis requires strong reasoning.
     */
    @Action(description = "Analyze code changes for bugs, security issues, and design problems")
    fun analyze(diff: ParsedDiff, ctx: OperationContext): ReviewAnalysis {
        val filesContext = diff.files.joinToString("\n---\n") { file ->
            """
            File: ${file.path} (${file.language})
            Changes: +${file.additions} -${file.deletions}
            ${file.hunks.joinToString("\n")}
            """.trimIndent()
        }

        val prompt = """
            Review these code changes carefully:

            $filesContext

            For each issue found, provide a review comment with:
            1. File and line number (if identifiable)
            2. Severity: CRITICAL (blocks merge), MAJOR (should fix), MINOR (nice to fix), SUGGESTION (optional improvement)
            3. Category: bug, security, performance, style, design
            4. Clear description of the issue
            5. Suggested fix (if applicable)

            Focus on:
            - Security vulnerabilities (injection, auth bypass, data exposure)
            - Logic bugs and edge cases
            - Error handling gaps
            - Performance issues (N+1 queries, unnecessary allocations)
            - Thread safety issues
            - API contract violations

            Do NOT comment on:
            - Formatting/whitespace (handled by formatters)
            - Import ordering
            - Trivial style preferences

            Return JSON with fields: comments (array), criticalCount, majorCount, patternsDetected (array of strings)
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("best")
            .creating(ReviewAnalysis::class.java)
            .create(prompt)
    }

    /**
     * Step 3: Summarize analysis into final review report.
     * Uses cheapest model — report formatting is straightforward.
     */
    @AchievesGoal(description = "Produce a final code review report with verdict")
    @Action(description = "Summarize review analysis into a final report")
    fun summarize(analysis: ReviewAnalysis, ctx: OperationContext): ReviewReport {
        val commentsSummary = analysis.comments.joinToString("\n") {
            "[${it.severity}/${it.category}] ${it.file}:${it.line ?: "?"} — ${it.message}"
        }

        val prompt = """
            Produce a final code review report from this analysis:

            Comments (${analysis.comments.size}):
            $commentsSummary

            Patterns detected: ${analysis.patternsDetected.joinToString(", ")}
            Critical issues: ${analysis.criticalCount}
            Major issues: ${analysis.majorCount}

            Generate:
            1. Executive summary (2-3 sentences)
            2. Verdict: APPROVE (no critical/major), REQUEST_CHANGES (has critical/major), COMMENT (only minor/suggestions)
            3. Top highlights (positive aspects of the code)
            4. Key suggestions for improvement

            Return JSON with fields: summary, verdict, comments (pass through), highlights (array), suggestions (array)
        """.trimIndent()

        return ctx.ai()
            .withLlmByRole("cheapest")
            .creating(ReviewReport::class.java)
            .create(prompt)
    }
}
```

### Step 5: Agent execution service (bridge between engine and Embabel)

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/EmbabelAgentService.kt`

```kotlin
package ai.kukuvaia.agents

import ai.kukuvaia.agents.model.ResearchReport
import ai.kukuvaia.agents.review.ReviewReport
import ai.kukuvaia.agents.validation.ValidationReport
import com.embabel.agent.api.AgentPlatform
import com.embabel.agent.domain.io.UserInput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Bridge between kukuvaia-core (Java) and Embabel agents (Kotlin).
 * Provides typed execution methods for each agent with goal type.
 */
@Service
class EmbabelAgentService(
    private val agentPlatform: AgentPlatform,
) {

    private val log = LoggerFactory.getLogger(EmbabelAgentService::class.java)

    /**
     * Execute the ResearchAgent for a given question.
     * GOAP planner resolves: UserInput → SearchResults → SynthesizedFindings → ResearchReport
     */
    fun research(question: String): ResearchReport {
        log.info("Executing ResearchAgent for: {}...", question.take(80))
        val input = UserInput(question, Instant.now())
        return agentPlatform.runAgentForResult(
            input = input,
            goalType = ResearchReport::class.java,
        )
    }

    /**
     * Execute the ValidationAgent for a document.
     * GOAP planner resolves: UserInput → DocumentReference → ValidationFindings → ValidationReport
     */
    fun validate(request: String): ValidationReport {
        log.info("Executing ValidationAgent for: {}...", request.take(80))
        val input = UserInput(request, Instant.now())
        return agentPlatform.runAgentForResult(
            input = input,
            goalType = ValidationReport::class.java,
        )
    }

    /**
     * Execute the CodeReviewAgent for a diff.
     * GOAP planner resolves: UserInput → ParsedDiff → ReviewAnalysis → ReviewReport
     */
    fun review(diff: String): ReviewReport {
        log.info("Executing CodeReviewAgent for: {}...", diff.take(80))
        val input = UserInput(diff, Instant.now())
        return agentPlatform.runAgentForResult(
            input = input,
            goalType = ReviewReport::class.java,
        )
    }
}
```

### Step 6: Tool exposure for agents

Embabel agents need access to kukuvaia tools (search_items, get_outline_raw, etc.) via ProcessContext. Two approaches:

**Option A: Embabel tool registration (preferred)**

Register kukuvaia tools with Embabel's tool system so agents can call them via `ctx.ai()`:

**File**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/config/EmbabelToolBridgeConfig.kt`

```kotlin
package ai.kukuvaia.agents.config

import ai.kukuvaia.config.ToolRegistryConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Bridges kukuvaia's ToolRegistryConfig to Embabel's tool system.
 * Makes all @Tool-annotated methods available to Embabel agents via ProcessContext.
 */
@Configuration
class EmbabelToolBridgeConfig(
    private val toolRegistry: ToolRegistryConfig,
) {

    @Bean
    fun embabelToolCallbacks(): List<org.springframework.ai.tool.ToolCallback> {
        // Expose all kukuvaia tools to Embabel agents
        return toolRegistry.toolNames().mapNotNull { name ->
            toolRegistry.resolve(name).orElse(null)
        }
    }
}
```

**Option B: Agent-specific tool filtering**

If agents should only access specific tools, filter per agent:

```kotlin
// In ResearchAgent, use tool annotations or ProcessContext configuration
// to limit tools to: search_memories, search_items, list_memories
```

### Step 7: Agent-as-tool integration

Expose Embabel agents as tools callable from the main ChatClient, so the LLM can autonomously invoke agents:

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/tools/AgentTools.java`

```java
package ai.kukuvaia.tools;

import ai.kukuvaia.agents.EmbabelAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Exposes Embabel agents as tools for the main ChatClient.
 * The LLM can invoke these when it determines a structured agent
 * is more appropriate than direct tool calling.
 */
@Component
public class AgentTools {

    private final EmbabelAgentService embabelService;
    private final ObjectMapper objectMapper;

    public AgentTools(EmbabelAgentService embabelService, ObjectMapper objectMapper) {
        this.embabelService = embabelService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Research a topic thoroughly using multi-step search, synthesis, and analysis. " +
            "Returns a structured report with sources. Use for complex questions requiring multiple searches.")
    public String research(@ToolParam(description = "Research question") String question) {
        var report = embabelService.research(question);
        return objectMapper.writeValueAsString(report);
    }

    @Tool(description = "Validate a document against quality rules and suggest fixes. " +
            "Returns a validation report with issues and suggested corrections.")
    public String validateDocument(
            @ToolParam(description = "Document to validate (ID, name, or description)") String request) {
        var report = embabelService.validate(request);
        return objectMapper.writeValueAsString(report);
    }

    @Tool(description = "Review code changes (diff) for bugs, security issues, and design problems. " +
            "Returns a review report with comments and verdict.")
    public String reviewCode(@ToolParam(description = "Code diff or description of changes") String diff) {
        var report = embabelService.review(diff);
        return objectMapper.writeValueAsString(report);
    }
}
```

### Step 8: Agent isolation

Each Embabel agent runs with its own LLM context via `ProcessContext`. The key isolation guarantees:

1. **Separate LLM calls**: Each `ctx.ai().withLlmByRole(...)` creates an independent LLM call
2. **No shared conversation**: Agents do not share conversation history
3. **Tool filtering**: Can be configured per-agent via Embabel's tool binding (Step 6)
4. **Model selection**: Per-action via `withLlmByRole()` — enforced by KukuvaiaModelProvider

For additional isolation (e.g., preventing agents from calling other agents recursively), the `AgentTools` can be excluded from the tool set provided to Embabel agents:

```kotlin
// In EmbabelToolBridgeConfig, filter out agent tools:
val agentToolNames = setOf("research", "validateDocument", "reviewCode")
toolRegistry.toolNames()
    .filter { it !in agentToolNames }
    .mapNotNull { toolRegistry.resolve(it).orElse(null) }
```

---

## Configuration

### application.yaml additions

```yaml
kukuvaia:
  agents:
    enabled: ${KUKUVAIA_AGENTS_ENABLED:true}
    research:
      search-model-role: cheapest
      analysis-model-role: best
      max-search-results: 20
    validation:
      load-model-role: cheapest
      suggest-model-role: best
    review:
      parse-model-role: cheapest
      analyze-model-role: best
      max-diff-size: 10000      # characters

embabel:
  models:
    default-llm: default
```

---

## Dependencies

| Dependency | Purpose | New? |
|-----------|---------|------|
| `embabel-agent-starter:0.3.4` | Agent framework, GOAP planner | Existing |
| `embabel-agent-test:0.3.4` | Test framework for agent verification | Existing |
| `embabel-common-ai:0.1.6` | ModelProvider, LLM abstraction | Existing |
| `KukuvaiaModelProvider` | Runtime-updatable model mapping | Existing |
| `ToolRegistryConfig` | Tool resolution for agent tool access | Existing |

No new external dependencies.

---

## File Inventory

### New files (12)

| File | Description |
|------|-------------|
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/model/AgentModels.kt` | Shared data types |
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/research/ResearchAgent.kt` | Research GOAP agent |
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/validation/ValidationModels.kt` | Validation data types |
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/validation/ValidationAgent.kt` | Validation GOAP agent |
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/review/ReviewModels.kt` | Code review data types |
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/review/CodeReviewAgent.kt` | Code review GOAP agent |
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/EmbabelAgentService.kt` | Java-Kotlin bridge service |
| `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/config/EmbabelToolBridgeConfig.kt` | Tool bridge for agents |
| `kukuvaia-core/src/main/java/ai/kukuvaia/tools/AgentTools.java` | Agents exposed as @Tool |

### Modified files (1)

| File | Change |
|------|--------|
| `kukuvaia-app/src/main/resources/application.yaml` | Add kukuvaia.agents.* section |

### Test files (4)

| File | What it tests |
|------|--------------|
| `kukuvaia-agents/src/test/kotlin/ai/kukuvaia/agents/research/ResearchAgentTest.kt` | GOAP plan resolution, action chain execution |
| `kukuvaia-agents/src/test/kotlin/ai/kukuvaia/agents/validation/ValidationAgentTest.kt` | Document loading, rule checking, fix generation |
| `kukuvaia-agents/src/test/kotlin/ai/kukuvaia/agents/review/CodeReviewAgentTest.kt` | Diff parsing, analysis, verdict generation |
| `kukuvaia-agents/src/test/kotlin/ai/kukuvaia/agents/EmbabelAgentServiceTest.kt` | Bridge service, platform invocation |

---

## Verification

### Unit tests (Embabel test framework)

```bash
./gradlew :kukuvaia-agents:test --tests "ai.kukuvaia.agents.*"
```

Using `embabel-agent-test` framework which provides:
- `AgentTestContext` — mock LLM responses, verify action execution order
- `PlanVerifier` — verify GOAP plan resolution without actual LLM calls

Expected:
- `ResearchAgentTest`: GOAP planner resolves `UserInput → SearchResults → SynthesizedFindings → ResearchReport`; action count = 3; model roles verified (cheapest, cheapest, best)
- `ValidationAgentTest`: GOAP planner resolves `UserInput → DocumentReference → ValidationFindings → ValidationReport`; severity classification correct
- `CodeReviewAgentTest`: GOAP planner resolves `UserInput → ParsedDiff → ReviewAnalysis → ReviewReport`; verdict logic correct

### Integration test (with mocked LLM)

```bash
./gradlew :kukuvaia-agents:test --tests "ai.kukuvaia.agents.EmbabelAgentServiceTest"
```

Tests `EmbabelAgentService` with:
- Mocked `AgentPlatform` that verifies correct goal types
- End-to-end agent execution with stubbed LLM responses

### Full integration test (with real LLM — manual)

```bash
# Requires: DB model roles configured (worker=haiku, advisor=opus, default=sonnet)
# Start the server
./gradlew :kukuvaia-app:bootRun

# Trigger research agent via chat (LLM chooses to call research tool)
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"agent-test","message":"Research the best practices for rate limiting in Spring Boot APIs. Give me a comprehensive report."}'

# Verify in logs:
# 1. "Executing ResearchAgent for: ..."
# 2. Multiple LLM calls with different models (cheapest for search/synthesize, best for analyze)
# 3. Structured ResearchReport returned

# Trigger code review agent
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"agent-test","message":"Review this code change: added a new endpoint POST /api/users that accepts username and password as query parameters"}'

# Verify: ReviewReport with CRITICAL security comment about credentials in query params
```

---

## Effort Estimate

| Phase | Scope | Effort |
|-------|-------|--------|
| Phase 1 | Shared agent models (AgentModels.kt) | 0.5 day |
| Phase 2 | ResearchAgent (3 actions) | 1.5 days |
| Phase 3 | ValidationAgent (3 actions) | 1.5 days |
| Phase 4 | CodeReviewAgent (3 actions) | 1.5 days |
| Phase 5 | EmbabelAgentService (bridge) | 0.5 day |
| Phase 6 | EmbabelToolBridgeConfig (tool access) | 0.5 day |
| Phase 7 | AgentTools (expose as @Tool) | 0.5 day |
| Phase 8 | Tests (unit with Embabel test framework) | 2 days |
| Phase 9 | Integration testing with real LLM | 1 day |
| **Total** | | **9.5 days** |

---

## Priority & Prerequisites

**Priority**: Medium — demonstrates the value of Embabel and GOAP planning. Important for the agent platform positioning.

**Prerequisites**:
- Embabel 0.3.4 wired and PingAgent passing (already done)
- KukuvaiaModelProvider operational (already done)
- DB model roles configured with at least "cheapest" and "best" roles (from model-routing-and-embabel.md Phase 1-2)
- Tool registry with searchable tools (already done)

**Blocked by**: Model roles must be configured in DB for per-action model selection to work. Without roles, all actions use the fallback model (functional but loses the cost optimization benefit).

**Blocks**:
- Production demonstration of Embabel value
- Agent marketplace / agent composition features
- Documentation showing GOAP vs simple tool calling benefits

**Risk**: Embabel 0.3.4 is pre-1.0 — API surface may change. The `ProcessContext` and `AgentPlatform` APIs need verification against the actual 0.3.4 release. The agent implementations use `ctx.ai().withLlmByRole().creating().create()` which is the documented Embabel pattern, but exact method signatures should be verified against the library source.

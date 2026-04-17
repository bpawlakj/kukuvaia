package ai.kukuvaia.extensions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RulesLoader — structured rule loading from .kukuvaia/rules/")
class RulesLoaderTest {

    @TempDir
    Path tempDir;
    private RulesLoader loader;

    @BeforeEach
    void setUp() {
        var extensionLoader = mock(ExtensionLoader.class);
        when(extensionLoader.getRulesDir()).thenReturn(Optional.of(tempDir));
        loader = new RulesLoader(extensionLoader);
    }

    @Test
    @DisplayName("structured rule with frontmatter — parses correctly")
    void load_structuredRule_parsesCorrectly() throws IOException {
        Files.writeString(tempDir.resolve("think-deep.md"), """
                ---
                name: think-deep
                description: Enable chain-of-thought reasoning
                type: behavior
                scope: global
                priority: 90
                ---

                Before answering any question:
                1. Break down the problem into components
                2. Analyze each component separately
                3. Synthesize findings into a coherent answer
                4. Verify your reasoning before responding
                """);

        loader.reload();
        var rules = loader.allRules();

        assertThat(rules).hasSize(1);
        var rule = rules.getFirst();
        assertThat(rule.name()).isEqualTo("think-deep");
        assertThat(rule.type()).isEqualTo(RuleSpec.RuleType.BEHAVIOR);
        assertThat(rule.scope()).isEqualTo(RuleSpec.RuleScope.GLOBAL);
        assertThat(rule.priority()).isEqualTo(90);
        assertThat(rule.body()).contains("Break down the problem");
    }

    @Test
    @DisplayName("format rule with intent scope — parses intents")
    void load_formatRuleWithIntent_parsesIntents() throws IOException {
        Files.writeString(tempDir.resolve("json-output.md"), """
                ---
                name: json-analysis
                description: Respond with JSON for analysis
                type: format
                scope: intent
                intents: [ANALYSIS]
                priority: 80
                ---

                Always respond in valid JSON with structure:
                { "result": "...", "confidence": 0.0-1.0, "reasoning": "..." }
                """);

        loader.reload();
        var rules = loader.allRules();

        assertThat(rules).hasSize(1);
        var rule = rules.getFirst();
        assertThat(rule.type()).isEqualTo(RuleSpec.RuleType.FORMAT);
        assertThat(rule.scope()).isEqualTo(RuleSpec.RuleScope.INTENT);
        assertThat(rule.intents()).containsExactly("ANALYSIS");
    }

    @Test
    @DisplayName("when shorthand — parses 'when: intent == X' to intents list")
    void load_whenShorthand_parsesToIntents() throws IOException {
        Files.writeString(tempDir.resolve("concise.md"), """
                ---
                name: concise-conversation
                type: behavior
                scope: intent
                when: intent == CONVERSATION
                ---

                Be extremely concise. Maximum 2 sentences.
                """);

        loader.reload();
        var rule = loader.allRules().getFirst();

        assertThat(rule.intents()).containsExactly("CONVERSATION");
    }

    @Test
    @DisplayName("plain MD without frontmatter — treated as global constraint")
    void load_plainMarkdown_treatedAsGlobalConstraint() throws IOException {
        Files.writeString(tempDir.resolve("no-secrets.md"),
                "Never include API keys, passwords, or connection strings in responses.");

        loader.reload();
        var rules = loader.allRules();

        assertThat(rules).hasSize(1);
        var rule = rules.getFirst();
        assertThat(rule.name()).isEqualTo("no-secrets");
        assertThat(rule.type()).isEqualTo(RuleSpec.RuleType.CONSTRAINT);
        assertThat(rule.scope()).isEqualTo(RuleSpec.RuleScope.GLOBAL);
        assertThat(rule.body()).contains("API keys");
    }

    @Test
    @DisplayName("persona-scoped rule — applies only to matching persona")
    void load_personaScopedRule_appliesToPersona() throws IOException {
        Files.writeString(tempDir.resolve("editor-thinking.md"), """
                ---
                name: editor-thinking
                type: behavior
                scope: persona
                persona: editor
                priority: 70
                ---

                As an editor, think through each change carefully.
                Consider: readability, consistency, grammar, tone.
                """);

        loader.reload();
        var applicable = loader.getApplicableRules("editor", null);
        assertThat(applicable).hasSize(1);

        var notApplicable = loader.getApplicableRules("assistant", null);
        assertThat(notApplicable).isEmpty();
    }

    @Test
    @DisplayName("multiple rules — sorted by priority descending")
    void load_multipleRules_sortedByPriority() throws IOException {
        Files.writeString(tempDir.resolve("low-priority.md"), """
                ---
                name: low
                type: constraint
                priority: 10
                ---
                Low priority rule.
                """);
        Files.writeString(tempDir.resolve("high-priority.md"), """
                ---
                name: high
                type: constraint
                priority: 90
                ---
                High priority rule.
                """);

        loader.reload();
        var rules = loader.getApplicableRules(null, null);

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).name()).isEqualTo("high");
        assertThat(rules.get(1).name()).isEqualTo("low");
    }

    @Test
    @DisplayName("buildRulesPrompt — groups by type with headers")
    void buildRulesPrompt_multipleTypes_groupedByType() throws IOException {
        Files.writeString(tempDir.resolve("constraint.md"), """
                ---
                name: no-secrets
                type: constraint
                ---
                Never expose secrets.
                """);
        Files.writeString(tempDir.resolve("behavior.md"), """
                ---
                name: think-step
                type: behavior
                ---
                Think step by step.
                """);
        Files.writeString(tempDir.resolve("format.md"), """
                ---
                name: use-tables
                type: format
                ---
                Use tables for structured data.
                """);

        loader.reload();
        String prompt = loader.buildRulesPrompt(null, null);

        assertThat(prompt)
                .contains("## Constraints (non-negotiable)")
                .contains("## Behavior Directives")
                .contains("## Output Format Requirements")
                .contains("Never expose secrets")
                .contains("Think step by step")
                .contains("Use tables");
    }

    @Test
    @DisplayName("buildRulesPrompt — filters by intent scope")
    void buildRulesPrompt_intentFilter_onlyMatchingRules() throws IOException {
        Files.writeString(tempDir.resolve("always.md"), """
                ---
                name: always-on
                type: constraint
                ---
                Always applies.
                """);
        Files.writeString(tempDir.resolve("analysis-only.md"), """
                ---
                name: analysis-json
                type: format
                scope: intent
                intents: [ANALYSIS]
                ---
                Respond in JSON for analysis tasks.
                """);

        loader.reload();

        String analysisPrompt = loader.buildRulesPrompt(null, "ANALYSIS");
        assertThat(analysisPrompt).contains("Always applies").contains("Respond in JSON");

        String conversationPrompt = loader.buildRulesPrompt(null, "CONVERSATION");
        assertThat(conversationPrompt).contains("Always applies").doesNotContain("Respond in JSON");
    }

    @Test
    @DisplayName("empty rules directory — returns empty prompt")
    void load_emptyDir_returnsEmptyPrompt() {
        loader.reload();
        assertThat(loader.buildRulesPrompt(null, null)).isEmpty();
        assertThat(loader.allRules()).isEmpty();
    }

    @Test
    @DisplayName("no rules directory — returns empty")
    void load_noDir_returnsEmpty() {
        var extensionLoader = mock(ExtensionLoader.class);
        when(extensionLoader.getRulesDir()).thenReturn(Optional.empty());
        var emptyLoader = new RulesLoader(extensionLoader);

        assertThat(emptyLoader.allRules()).isEmpty();
        assertThat(emptyLoader.loadRules()).isEmpty();
    }
}

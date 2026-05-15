package ai.kukuvaia.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip + format validation for {@link HarnessRuleParser}. The parser is internal but its
 * format is the operator contract — every assertion here doubles as documentation of "what
 * markdown shape will the admin panel actually accept".
 */
@DisplayName("HarnessRuleParser — frontmatter + body parsing")
class HarnessRuleParserTest {

    @Test
    @DisplayName("happy path — minimal frontmatter (type + body) parses with name as key, platform scope")
    void minimalFrontmatter() {
        String md = """
                ---
                type: instruction
                ---
                Use constructor injection only.
                """;
        HarnessRule rule = HarnessRuleParser.parse("di-policy", md);

        assertThat(rule.name()).isEqualTo("di-policy");
        assertThat(rule.key()).isEqualTo("di-policy");
        assertThat(rule.scope()).isEqualTo("platform");
        assertThat(rule.type()).isEqualTo("instruction");
        assertThat(rule.content()).isEqualTo("Use constructor injection only.");
        assertThat(rule.priority()).isZero();
        assertThat(rule.enabled()).isTrue();
        assertThat(rule.tags()).isEmpty();
    }

    @Test
    @DisplayName("full frontmatter — all fields override defaults")
    void fullFrontmatter() {
        String md = """
                ---
                key: di
                scope: user:bartek
                type: constraint
                priority: 10
                enabled: false
                tags: [java, di, override]
                ---
                Field injection forbidden in this codebase.
                """;
        HarnessRule rule = HarnessRuleParser.parse("verbose-di", md);

        assertThat(rule.key()).isEqualTo("di");
        assertThat(rule.scope()).isEqualTo("user:bartek");
        assertThat(rule.type()).isEqualTo("constraint");
        assertThat(rule.priority()).isEqualTo(10);
        assertThat(rule.enabled()).isFalse();
        assertThat(rule.tags()).containsExactly("java", "di", "override");
    }

    @Test
    @DisplayName("missing 'type' frontmatter field throws IllegalArgumentException")
    void missingType_throws() {
        String md = """
                ---
                key: x
                ---
                body content
                """;
        assertThatThrownBy(() -> HarnessRuleParser.parse("x", md))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'type' is required");
    }

    @Test
    @DisplayName("missing frontmatter delimiter throws IllegalArgumentException")
    void noFrontmatter_throws() {
        assertThatThrownBy(() -> HarnessRuleParser.parse("x", "just a body, no frontmatter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '---'");
    }

    @Test
    @DisplayName("unclosed frontmatter throws IllegalArgumentException")
    void unclosedFrontmatter_throws() {
        assertThatThrownBy(() -> HarnessRuleParser.parse("x", "---\ntype: instruction\nbody but no closing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    @DisplayName("empty body throws IllegalArgumentException")
    void emptyBody_throws() {
        String md = """
                ---
                type: instruction
                ---
                """;
        assertThatThrownBy(() -> HarnessRuleParser.parse("x", md))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body must not be empty");
    }

    @Test
    @DisplayName("Windows line endings are normalised")
    void crlfNormalised() {
        String md = "---\r\ntype: instruction\r\n---\r\nWindows newlines fine.\r\n";
        HarnessRule rule = HarnessRuleParser.parse("crlf", md);
        assertThat(rule.content()).isEqualTo("Windows newlines fine.");
    }

    @Test
    @DisplayName("render → parse round-trip preserves all fields")
    void renderParseRoundTrip() {
        HarnessRule original = new HarnessRule(
                "rule-x", "rule-x", "platform", "preference", "Be concise.",
                5, true, java.util.List.of("a", "b"));

        String rendered = HarnessRuleParser.render(original);
        HarnessRule parsed = HarnessRuleParser.parse("rule-x", rendered);

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    @DisplayName("invalid YAML in frontmatter surfaces the parse error in the message")
    void invalidYaml_surfacesError() {
        String md = """
                ---
                type: instruction
                tags: [unterminated
                ---
                body
                """;
        assertThatThrownBy(() -> HarnessRuleParser.parse("bad", md))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid YAML frontmatter");
    }
}

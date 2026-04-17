package ai.kukuvaia.extensions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RuleSpec — structured rule definition")
class RuleSpecTest {

    @Test
    @DisplayName("valid constraint — creates with defaults")
    void constructor_validConstraint_createsWithDefaults() {
        var rule = new RuleSpec("no-secrets", "Never expose secrets", null, null, 0, null, null,
                "Never include API keys, passwords, or tokens in responses.");

        assertThat(rule.type()).isEqualTo(RuleSpec.RuleType.CONSTRAINT);
        assertThat(rule.scope()).isEqualTo(RuleSpec.RuleScope.GLOBAL);
        assertThat(rule.priority()).isEqualTo(50);
        assertThat(rule.intents()).isEmpty();
    }

    @Test
    @DisplayName("null name — throws")
    void constructor_nullName_throws() {
        assertThatThrownBy(() -> new RuleSpec(null, "d", null, null, 0, null, null, "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("empty body — throws")
    void constructor_emptyBody_throws() {
        assertThatThrownBy(() -> new RuleSpec("test", "d", null, null, 0, null, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body is required");
    }

    // === appliesTo tests ===

    @Test
    @DisplayName("GLOBAL scope — applies to everything")
    void appliesTo_globalScope_alwaysTrue() {
        var rule = new RuleSpec("global-rule", "", RuleSpec.RuleType.CONSTRAINT,
                RuleSpec.RuleScope.GLOBAL, 50, null, List.of(), "Always apply.");

        assertThat(rule.appliesTo("editor", "ANALYSIS")).isTrue();
        assertThat(rule.appliesTo(null, null)).isTrue();
    }

    @Test
    @DisplayName("PERSONA scope — applies only to matching persona")
    void appliesTo_personaScope_matchesPersona() {
        var rule = new RuleSpec("editor-rule", "", RuleSpec.RuleType.BEHAVIOR,
                RuleSpec.RuleScope.PERSONA, 50, "editor", List.of(), "Think deeply.");

        assertThat(rule.appliesTo("editor", "ANALYSIS")).isTrue();
        assertThat(rule.appliesTo("assistant", "ANALYSIS")).isFalse();
        assertThat(rule.appliesTo(null, null)).isFalse();
    }

    @Test
    @DisplayName("INTENT scope — applies when intent matches")
    void appliesTo_intentScope_matchesIntent() {
        var rule = new RuleSpec("analysis-json", "", RuleSpec.RuleType.FORMAT,
                RuleSpec.RuleScope.INTENT, 50, null, List.of("ANALYSIS"), "Respond in JSON.");

        assertThat(rule.appliesTo(null, "ANALYSIS")).isTrue();
        assertThat(rule.appliesTo(null, "CONVERSATION")).isFalse();
    }

    @Test
    @DisplayName("INTENT scope with empty intents — applies to all intents")
    void appliesTo_intentScopeEmptyList_appliesToAll() {
        var rule = new RuleSpec("any-intent", "", RuleSpec.RuleType.BEHAVIOR,
                RuleSpec.RuleScope.INTENT, 50, null, List.of(), "Always think.");

        assertThat(rule.appliesTo(null, "ANALYSIS")).isTrue();
        assertThat(rule.appliesTo(null, "CONVERSATION")).isTrue();
    }

    @Test
    @DisplayName("INTENT scope with multiple intents — matches any")
    void appliesTo_multipleIntents_matchesAny() {
        var rule = new RuleSpec("rw-rule", "", RuleSpec.RuleType.FORMAT,
                RuleSpec.RuleScope.INTENT, 50, null,
                List.of("DOCUMENT_READ", "DOCUMENT_WRITE"), "Use structured output.");

        assertThat(rule.appliesTo(null, "DOCUMENT_READ")).isTrue();
        assertThat(rule.appliesTo(null, "DOCUMENT_WRITE")).isTrue();
        assertThat(rule.appliesTo(null, "CONVERSATION")).isFalse();
    }
}

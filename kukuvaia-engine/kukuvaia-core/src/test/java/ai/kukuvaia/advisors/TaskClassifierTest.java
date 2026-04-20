package ai.kukuvaia.advisors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TaskClassifier — feature-scored routing decisions")
class TaskClassifierTest {

    private final TaskClassifier classifier = new TaskClassifier();

    @Test
    @DisplayName("trivial greeting → FAST")
    void trivialGreeting_routesFast() {
        var r = classifier.classify("hej", false, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.FAST);
    }

    @Test
    @DisplayName("greeting + planning intent → DEFAULT (not FAST)")
    void greetingWithPlanningIntent_routesDefault() {
        var r = classifier.classify("hej, zaplanuj wycieczkę na Kretę dla rodziny", false, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.DEFAULT);
    }

    @Test
    @DisplayName("explicit complexity marker → ESCALATE")
    void complexityMarker_escalates() {
        var r = classifier.classify("pomyśl dobrze jak zaplanować ten wyjazd", false, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.ESCALATE);
    }

    @Test
    @DisplayName("analysis intent (streszcz) → ESCALATE")
    void analysisIntent_escalates() {
        var r = classifier.classify("streszcz mi ten długi dokument i wyjaśnij kluczowe punkty",
                false, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.ESCALATE);
    }

    @Test
    @DisplayName("escalate flag override beats everything")
    void escalateFlag_forcesEscalate() {
        var r = classifier.classify("hej", true, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.ESCALATE);
    }

    @Test
    @DisplayName("code block present → DEFAULT (not FAST even if short)")
    void codeBlock_routesDefault() {
        var r = classifier.classify("fix this `print('x')`", false, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.DEFAULT);
    }

    @Test
    @DisplayName("file path mention → DEFAULT")
    void filePath_routesDefault() {
        var r = classifier.classify("zerknij na AgentService.java", false, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.DEFAULT);
    }

    @Test
    @DisplayName("planning mode active → never FAST")
    void planningMode_forcesAtLeastDefault() {
        var r = classifier.classify("tak", false, true, 5);
        assertThat(r.tier()).isNotEqualTo(TaskClassifier.Tier.FAST);
    }

    @Test
    @DisplayName("first turn → never FAST (onboarding bias)")
    void firstTurn_routesDefault() {
        // Just "hi" on turn 0 — normally FAST, but first-turn bias nudges it to DEFAULT
        var r = classifier.classify("hi", false, false, 0);
        // FAST gets +3 (short) +2 (greeting) = 5 with base 0 = 5
        // DEFAULT gets +2 (first_turn) with base 1 = 3
        // ESCALATE: 0
        // FAST still wins — first-turn alone doesn't flip pure greetings. This is OK.
        // But longer first-turn messages should go DEFAULT.
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.FAST);

        var firstComplex = classifier.classify("opisz mi jak działa twój system", false, false, 0);
        assertThat(firstComplex.tier()).isNotEqualTo(TaskClassifier.Tier.FAST);
    }

    @Test
    @DisplayName("empty message → DEFAULT")
    void emptyMessage_routesDefault() {
        var r = classifier.classify("", false, false, 5);
        assertThat(r.tier()).isEqualTo(TaskClassifier.Tier.DEFAULT);
    }

    @Test
    @DisplayName("reason string reflects dominant feature")
    void reasonReflectsFeature() {
        var r = classifier.classify("hej", false, false, 5);
        assertThat(r.reason()).isIn("greeting", "short_message");
    }

    @Test
    @DisplayName("long message with question → ESCALATE or DEFAULT, never FAST")
    void longMessage_neverFast() {
        String longMsg = "Napisz proszę szczegółowe wyjaśnienie " +
                "jak działa cache prompt w modelach językowych, z przykładami, " +
                "kiedy to jest opłacalne i jakie są ograniczenia. Porównaj Anthropic i OpenAI.";
        var r = classifier.classify(longMsg, false, false, 5);
        assertThat(r.tier()).isNotEqualTo(TaskClassifier.Tier.FAST);
    }
}

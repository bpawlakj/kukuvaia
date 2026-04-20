package ai.kukuvaia.advisors;

import ai.kukuvaia.provider.registry.TaskComplexity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StructuralHeuristics — structural signals only, no keyword matching")
class StructuralHeuristicsTest {

    private final StructuralHeuristics heuristics = new StructuralHeuristics();

    @Test
    @DisplayName("short greeting ('hej') → RETRIEVAL top")
    void shortMessage_retrievalTop() {
        var r = heuristics.score("hej", false, false);
        assertThat(r.top()).isEqualTo(TaskComplexity.RETRIEVAL);
    }

    @Test
    @DisplayName("empty message → GENERATION top (safe supervisor fallback)")
    void emptyMessage_generationTop() {
        var r = heuristics.score("", false, false);
        assertThat(r.top()).isEqualTo(TaskComplexity.GENERATION);
    }

    @Test
    @DisplayName("long multi-sentence message → SYNTHESIS top")
    void longMultiSentence_synthesisTop() {
        String msg = "Proszę streścić całą zawartość tego dokumentu. "
                + "Podkreśl kluczowe wnioski i wskaż ryzyka. "
                + "Sformułuj też rekomendacje, co zrobić w następnej iteracji. "
                + "Chcę to mieć w punktach. "
                + "Każdy punkt powinien być opisany w jednym zdaniu. "
                + "To jest naprawdę ważny dokument i potrzebuję solidnego podsumowania, "
                + "żeby móc przekazać to dalej zespołowi.";
        assertThat(msg.length()).isGreaterThan(300);
        var r = heuristics.score(msg, false, false);
        assertThat(r.top()).isEqualTo(TaskComplexity.SYNTHESIS);
    }

    @Test
    @DisplayName("multiple questions → ANALYSIS top")
    void multipleQuestions_analysisTop() {
        var r = heuristics.score(
                "czy A jest lepsze niż B? jak to zmierzyć? co by to oznaczało?",
                false, false);
        assertThat(r.top()).isEqualTo(TaskComplexity.ANALYSIS);
    }

    @Test
    @DisplayName("code block present → GENERATION top")
    void codeBlock_generationTop() {
        var r = heuristics.score("```python\nprint('hello')\n```", false, false);
        assertThat(r.top()).isEqualTo(TaskComplexity.GENERATION);
    }

    @Test
    @DisplayName("file path reference boosts GENERATION")
    void filePath_generationBoost() {
        var r = heuristics.score("can you fix the bug in src/main/App.java", false, false);
        assertThat(r.scores().get(TaskComplexity.GENERATION))
                .isGreaterThan(r.scores().get(TaskComplexity.EXTRACTION));
    }

    @Test
    @DisplayName("planning mode → STRATEGY top regardless of message content")
    void planningMode_strategyTop() {
        var r = heuristics.score("i to by było wszystko", true, false);
        assertThat(r.top()).isEqualTo(TaskComplexity.STRATEGY);
    }

    @Test
    @DisplayName("first-turn bias nudges GENERATION")
    void firstTurn_generationNudge() {
        var r = heuristics.score("hello kukuvaia what can you do", false, true);
        assertThat(r.scores().get(TaskComplexity.GENERATION)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("confidence is 0 when only default bias fires (low-signal message)")
    void lowSignalMessage_zeroConfidence() {
        // Medium prose with no structural triggers → only GENERATION default +1.
        // topScore<2 forces confidence to 0, so LLM fallback can take over.
        var r = heuristics.score(
                "the quarterly rollup looks acceptable overall",
                false, false);
        assertThat(r.confidence()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("confidence is positive when heuristic has a clear winner")
    void clearWinner_positiveConfidence() {
        var r = heuristics.score("hej", false, false);
        assertThat(r.confidence()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("scores include all 9 TaskComplexity values")
    void scores_containAllComplexities() {
        var r = heuristics.score("anything", false, false);
        for (TaskComplexity c : TaskComplexity.values()) {
            assertThat(r.scores()).containsKey(c);
        }
    }

    @Test
    @DisplayName("planning mode with long multi-sentence → STRATEGY beats SYNTHESIS")
    void planningModeOverridesLength() {
        String msg = "Dokładnie opracujmy kolejne kroki. "
                + "Po pierwsze, zróbmy research. "
                + "Po drugie, stwórzmy prototyp. "
                + "Po trzecie, zmierzmy wyniki.";
        var r = heuristics.score(msg, true, false);
        assertThat(r.top()).isEqualTo(TaskComplexity.STRATEGY);
    }

    @Test
    @DisplayName("no hardcoded word triggers STRATEGY — only structural signals")
    void noHardcodedKeywords() {
        // The word "plan" used to trigger planning intent. Now nothing structural
        // about it → it should NOT force STRATEGY without planning mode.
        var r = heuristics.score("plan", false, false);
        assertThat(r.top()).isNotEqualTo(TaskComplexity.STRATEGY);
    }
}

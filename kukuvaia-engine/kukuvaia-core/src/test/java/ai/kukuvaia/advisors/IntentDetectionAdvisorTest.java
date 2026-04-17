package ai.kukuvaia.advisors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntentDetectionAdvisor — user intent classification")
class IntentDetectionAdvisorTest {

    private IntentDetectionAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new IntentDetectionAdvisor();
    }

    @Test
    @DisplayName("classify — read keywords → DOCUMENT_READ")
    void classify_readKeywords_returnsDocumentRead() {
        assertThat(advisor.classify("show me the document")).isEqualTo(IntentDetectionAdvisor.Intent.DOCUMENT_READ);
        assertThat(advisor.classify("search for items")).isEqualTo(IntentDetectionAdvisor.Intent.DOCUMENT_READ);
    }

    @Test
    @DisplayName("classify — write keywords → DOCUMENT_WRITE")
    void classify_writeKeywords_returnsDocumentWrite() {
        assertThat(advisor.classify("edit the document")).isEqualTo(IntentDetectionAdvisor.Intent.DOCUMENT_WRITE);
        assertThat(advisor.classify("create a new file")).isEqualTo(IntentDetectionAdvisor.Intent.DOCUMENT_WRITE);
    }

    @Test
    @DisplayName("classify — analysis keywords → ANALYSIS")
    void classify_analysisKeywords_returnsAnalysis() {
        assertThat(advisor.classify("analyze the validation results")).isEqualTo(IntentDetectionAdvisor.Intent.ANALYSIS);
        assertThat(advisor.classify("compare these documents")).isEqualTo(IntentDetectionAdvisor.Intent.ANALYSIS);
    }

    @Test
    @DisplayName("classify — no keywords → CONVERSATION")
    void classify_noKeywords_returnsConversation() {
        assertThat(advisor.classify("hello how are you")).isEqualTo(IntentDetectionAdvisor.Intent.CONVERSATION);
    }

    @Test
    @DisplayName("classify — analysis takes priority over read/write")
    void classify_mixedKeywords_analysisTakesPriority() {
        assertThat(advisor.classify("analyze and edit the report")).isEqualTo(IntentDetectionAdvisor.Intent.ANALYSIS);
    }
}

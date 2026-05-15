package ai.kukuvaia.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.kukuvaia.output.ChoiceBlock;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.SessionOutputSink;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserChoiceTools — emits ChoiceBlock + tells LLM to stop")
class UserChoiceToolsTest {

    @Mock SessionOutputSink outputSink;
    private UserChoiceTools tools;

    @BeforeEach
    void setUp() {
        tools = new UserChoiceTools(outputSink);
        PlanningTools.setContext("session-1", "user-1");
    }

    @AfterEach
    void tearDown() {
        PlanningTools.clearContext();
    }

    @Test
    @DisplayName("happy path — emits a ChoiceBlock with a fresh choiceId and returns stop instruction")
    void askUserToChoose_happyPath() {
        Map<String, Object> result = tools.askUserToChoose(
                "Which template?",
                List.of(
                        new UserChoiceTools.ChoiceOptionInput("t-1", "2018-08-21 Sisko MR", "PUBLISHED · sanomapro"),
                        new UserChoiceTools.ChoiceOptionInput("t-2", "Sisko MR Utbildning", "PUBLISHED · sanomapro")));

        assertThat(result).containsEntry("ok", true).containsEntry("presented", 2).containsKey("choiceId");
        assertThat(result.get("instruction").toString())
                .as("instruction MUST tell the LLM to stop the turn — otherwise it'll keep tool-calling")
                .contains("STOP");

        ArgumentCaptor<OutputBlock> captor = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(org.mockito.ArgumentMatchers.eq("session-1"), captor.capture());
        ChoiceBlock emitted = (ChoiceBlock) captor.getValue();
        assertThat(emitted.prompt()).isEqualTo("Which template?");
        assertThat(emitted.options()).hasSize(2);
        assertThat(emitted.options().get(0).value()).isEqualTo("t-1");
        assertThat(emitted.choiceId()).isEqualTo(result.get("choiceId"));
    }

    @Test
    @DisplayName("blank prompt → ok=false, no block emitted (the LLM gets a structured retry hint)")
    void askUserToChoose_blankPrompt() {
        Map<String, Object> result = tools.askUserToChoose(
                "   ",
                List.of(new UserChoiceTools.ChoiceOptionInput("t-1", "x", "y")));

        assertThat(result).containsEntry("ok", false);
        verify(outputSink, never()).emit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("empty options → ok=false, no block emitted")
    void askUserToChoose_emptyOptions() {
        Map<String, Object> result = tools.askUserToChoose("Pick one", List.of());

        assertThat(result).containsEntry("ok", false);
        verify(outputSink, never()).emit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("options whose value is null/blank are dropped — but at least one must remain")
    void askUserToChoose_dropsOptionsWithoutValue() {
        Map<String, Object> result = tools.askUserToChoose(
                "Pick one",
                java.util.Arrays.asList(
                        new UserChoiceTools.ChoiceOptionInput(null, "no value", "x"),
                        new UserChoiceTools.ChoiceOptionInput("t-2", "good", "y"),
                        new UserChoiceTools.ChoiceOptionInput("   ", "blank value", "z")));

        assertThat(result).containsEntry("ok", true).containsEntry("presented", 1);
        ArgumentCaptor<OutputBlock> captor = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(org.mockito.ArgumentMatchers.eq("session-1"), captor.capture());
        ChoiceBlock emitted = (ChoiceBlock) captor.getValue();
        assertThat(emitted.options()).hasSize(1);
        assertThat(emitted.options().get(0).value()).isEqualTo("t-2");
    }

    @Test
    @DisplayName("missing session context → ok=false (rejects mis-wired call rather than emitting nowhere)")
    void askUserToChoose_noSessionContext() {
        PlanningTools.clearContext(); // no session bound

        Map<String, Object> result = tools.askUserToChoose(
                "Pick one",
                List.of(new UserChoiceTools.ChoiceOptionInput("t-1", "a", "b")));

        assertThat(result).containsEntry("ok", false);
        verify(outputSink, never()).emit(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}

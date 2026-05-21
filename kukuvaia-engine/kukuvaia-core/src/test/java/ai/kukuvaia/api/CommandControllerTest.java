package ai.kukuvaia.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.commands.CommandRegistry;
import ai.kukuvaia.commands.SlashCommand;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommandController — persona is applied before slash-command dispatch")
class CommandControllerTest {

    @Mock CommandRegistry commandRegistry;
    @Mock PersonaService personaService;
    @Mock SlashCommand command;
    @InjectMocks CommandController controller;

    @Test
    @DisplayName("executeCommand — persona in body binds session BEFORE command runs")
    void executeCommand_persona_binds() {
        when(commandRegistry.resolve("help")).thenReturn(Optional.of(command));
        when(command.execute("", "session-1")).thenReturn(List.of(new TextBlock("help text", null)));

        ResponseEntity<List<OutputBlock>> response = controller.executeCommand(
                "help",
                Map.of("args", "", "sessionId", "session-1", "persona", "test-persona"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(personaService).applyIfPresent("session-1", "test-persona");
        verify(command).execute("", "session-1");
    }

    @Test
    @DisplayName("executeCommand — body without persona key passes null (no-op)")
    void executeCommand_noPersona_callsApplyIfPresentWithNull() {
        when(commandRegistry.resolve("help")).thenReturn(Optional.of(command));
        when(command.execute("", "session-1")).thenReturn(List.of());

        controller.executeCommand("help", Map.of("args", "", "sessionId", "session-1"));

        // applyIfPresent is always called — null/missing means no-op inside service
        verify(personaService).applyIfPresent("session-1", null);
    }

    @Test
    @DisplayName("executeCommand — unknown persona returns 400 and SKIPS the command")
    void executeCommand_unknownPersona_returns400() {
        Mockito.doThrow(new IllegalArgumentException("Unknown persona: ghost"))
                .when(personaService).applyIfPresent("session-1", "ghost");

        ResponseEntity<List<OutputBlock>> response = controller.executeCommand(
                "help",
                Map.of("sessionId", "session-1", "persona", "ghost"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).hasSize(1);
        TextBlock tb = (TextBlock) response.getBody().get(0);
        assertThat(tb.style()).isEqualTo("error");
        assertThat(tb.content()).contains("ghost");

        verify(commandRegistry, never()).resolve(anyString());
        verify(command, never()).execute(any(), any());
    }
}

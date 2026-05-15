package ai.kukuvaia.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.kukuvaia.agent.CommandRouter;
import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController — persona binding round-trip from KUKUVAIA_PERSONA env var")
class ChatControllerTest {

    @Mock CommandRouter commandRouter;
    @Mock PersonaService personaService;
    @InjectMocks ChatController controller;

    @BeforeEach
    void setUp() {
        // Default — router returns one TextBlock so we can assert downstream pass-through.
        // lenient() because the unknown-persona test short-circuits before routing.
        Mockito.lenient().when(commandRouter.route(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Flux.just(new TextBlock("ok", null)));
    }

    @Test
    @DisplayName("chat — non-blank persona binds session BEFORE routing")
    void chat_persona_binds() {
        var request = new ChatRequest("session-1", "hello", "rule-editor");

        List<OutputBlock> blocks = controller.chat(request).collectList().block();

        assertThat(blocks).hasSize(1);
        verify(personaService).applyIfPresent(eq("session-1"), eq("rule-editor"));
        verify(commandRouter).route("hello", "session-1");
    }

    @Test
    @DisplayName("chat — null persona stays no-op (legacy clients keep working)")
    void chat_persona_null_noOp() {
        var request = new ChatRequest("session-1", "hello", null);

        List<OutputBlock> blocks = controller.chat(request).collectList().block();

        assertThat(blocks).hasSize(1);
        verify(personaService).applyIfPresent("session-1", null);
        verify(commandRouter).route("hello", "session-1");
    }

    @Test
    @DisplayName("chat — unknown persona returns single error TextBlock and SKIPS router")
    void chat_persona_unknown_returnsErrorBlock() {
        Mockito.doThrow(new IllegalArgumentException("Unknown persona: ghost"))
                .when(personaService).applyIfPresent("session-1", "ghost");

        List<OutputBlock> blocks = controller.chat(new ChatRequest("session-1", "hello", "ghost"))
                .collectList().block();

        assertThat(blocks).hasSize(1);
        TextBlock tb = (TextBlock) blocks.get(0);
        assertThat(tb.style()).isEqualTo("error");
        assertThat(tb.content()).contains("ghost");

        verify(commandRouter, never()).route(Mockito.anyString(), Mockito.anyString());
    }
}

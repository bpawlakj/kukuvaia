package ai.kukuvaia.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.memory.repository.SessionRepository;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionController — PUT /api/sessions/{id}/persona explicit binding endpoint")
class SessionControllerPersonaTest {

    @Mock ChatMemoryRepository chatMemoryRepository;
    @Mock SessionRepository sessionRepository;
    @Mock PlanningModeService planningModeService;
    @Mock PersonaService personaService;
    @InjectMocks SessionController controller;

    @Test
    @DisplayName("setPersona — happy path: 200 with sessionId+persona echoed")
    void setPersona_happyPath() {
        ResponseEntity<Map<String, Object>> response = controller.setPersona(
                "session-1", Map.of("persona", "test-persona"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("sessionId", "session-1")
                .containsEntry("persona", "test-persona");
        verify(personaService).setActivePersona("session-1", "test-persona");
    }

    @Test
    @DisplayName("setPersona — blank persona → 400 (the operator probably forgot a value)")
    void setPersona_blankPersona_400() {
        ResponseEntity<Map<String, Object>> response = controller.setPersona(
                "session-1", Map.of("persona", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    @DisplayName("setPersona — unknown persona → 400 with the underlying message")
    void setPersona_unknown_400() {
        Mockito.doThrow(new IllegalArgumentException("Unknown persona: ghost"))
                .when(personaService).setActivePersona("session-1", "ghost");

        ResponseEntity<Map<String, Object>> response = controller.setPersona(
                "session-1", Map.of("persona", "ghost"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error").toString()).contains("ghost");
    }
}

package ai.kukuvaia.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PersonaService — persona loading and session management")
class PersonaServiceTest {

    private PersonaService personaService;

    @BeforeEach
    void setUp() {
        personaService = new PersonaService();
    }

    @Test
    @DisplayName("getActivePersona — returns default persona for new session")
    void getActivePersona_newSession_returnsDefault() {
        var persona = personaService.getActivePersona("new-session");

        assertThat(persona.name()).isEqualTo("assistant");
        assertThat(persona.systemPrompt()).contains("Kukuvaia");
    }

    @Test
    @DisplayName("allPersonas — contains at least default persona")
    void allPersonas_afterInit_containsDefault() {
        assertThat(personaService.allPersonas()).containsKey("assistant");
    }

    @Test
    @DisplayName("setActivePersona — throws for unknown persona")
    void setActivePersona_unknownPersona_throws() {
        assertThatThrownBy(() -> personaService.setActivePersona("session-1", "nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }
}

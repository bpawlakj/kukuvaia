package ai.kukuvaia.agent;

import ai.kukuvaia.extensions.ExtensionLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    @DisplayName("applyIfPresent — null/blank input is a no-op (does not bind, does not throw)")
    void applyIfPresent_nullOrBlank_noOp() {
        personaService.applyIfPresent("s1", null);
        personaService.applyIfPresent("s1", "");
        personaService.applyIfPresent("s1", "   ");

        // Session never bound — still resolves to default
        assertThat(personaService.getActivePersona("s1").name()).isEqualTo("assistant");
    }

    @Test
    @DisplayName("applyIfPresent — non-blank known persona delegates to setActivePersona")
    void applyIfPresent_knownPersona_binds(@TempDir Path personasDir) throws Exception {
        writeTestPersona(personasDir, "test-persona", "tool_a");
        var service = newServiceWithPersonasDir(personasDir);

        service.applyIfPresent("s1", "test-persona");

        assertThat(service.getActivePersona("s1").name()).isEqualTo("test-persona");
    }

    @Test
    @DisplayName("applyIfPresent — unknown persona throws (controllers turn this into 400)")
    void applyIfPresent_unknownPersona_throws() {
        assertThatThrownBy(() -> personaService.applyIfPresent("s1", "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("namedPersonaToolUnion — excludes default persona, unions every named filter")
    void namedPersonaToolUnion_excludesDefault_unionsNamed(@TempDir Path personasDir) throws Exception {
        writeTestPersona(personasDir, "alpha", "tool_a", "shared_tool");
        writeTestPersona(personasDir, "beta", "tool_b", "shared_tool");
        var service = newServiceWithPersonasDir(personasDir);

        Set<String> union = service.namedPersonaToolUnion();

        // Tools listed by any named persona surface in the union — that's how /help splits
        // "persona-specific" from "general" tools.
        assertThat(union).contains("tool_a", "tool_b", "shared_tool");

        // The default persona contributes nothing — its filter is empty.
        assertThat(union).doesNotContain("assistant_only_tool");
    }

    @Test
    @DisplayName("getActivePersona — FULL verbosity uses the proactivity-rules prompt")
    void getActivePersona_fullVerbosity_usesFullPrompt() {
        var service = new PersonaService(SupervisorVerbosity.FULL);

        var prompt = service.getActivePersona("s").systemPrompt();

        assertThat(prompt).contains("Proactivity rules");
        assertThat(prompt.split("\\s+")).hasSizeGreaterThan(100);
    }

    @Test
    @DisplayName("getActivePersona — CONCISE verbosity uses a short imperative prompt (<60 words)")
    void getActivePersona_conciseVerbosity_usesShortPrompt() {
        var service = new PersonaService(SupervisorVerbosity.CONCISE);

        var prompt = service.getActivePersona("s").systemPrompt();

        assertThat(prompt).doesNotContain("Proactivity rules");
        assertThat(prompt.split("\\s+")).hasSizeLessThan(60);
        assertThat(prompt).startsWith("You are Kukuvaia.");
    }

    private static PersonaService newServiceWithPersonasDir(Path personasDir) {
        ExtensionLoader extensionLoader = mock(ExtensionLoader.class);
        when(extensionLoader.getPersonasDir()).thenReturn(java.util.Optional.of(personasDir));
        return new PersonaService(SupervisorVerbosity.FULL, extensionLoader);
    }

    private static void writeTestPersona(Path dir, String name, String... tools) throws Exception {
        StringBuilder yaml = new StringBuilder();
        yaml.append("name: ").append(name).append('\n');
        yaml.append("description: test persona\n");
        yaml.append("system_prompt: |\n  You are ").append(name).append(".\n");
        yaml.append("tools:\n");
        for (String tool : tools) {
            yaml.append("  - ").append(tool).append('\n');
        }
        Files.writeString(dir.resolve(name + ".yaml"), yaml.toString());
    }
}

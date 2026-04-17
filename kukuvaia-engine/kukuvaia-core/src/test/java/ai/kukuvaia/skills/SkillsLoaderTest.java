package ai.kukuvaia.skills;

import ai.kukuvaia.extensions.ExtensionLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SkillsLoader — SKILL.md parsing from .kukuvaia/skills/")
class SkillsLoaderTest {

    @TempDir
    Path tempDir;
    private SkillsLoader loader;

    @BeforeEach
    void setUp() {
        var extensionLoader = mock(ExtensionLoader.class);
        when(extensionLoader.getSkillsDir()).thenReturn(Optional.of(tempDir));
        loader = new SkillsLoader(extensionLoader);
    }

    @Test
    @DisplayName("loads valid script skill with frontmatter and JS block")
    void loadSkills_validScript_loadsCorrectly() throws IOException {
        Path skillDir = tempDir.resolve("check-links");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: check-links
                description: Check broken links
                trigger: /check-links
                type: script
                timeout: 3000
                tools: [readDocument]
                ---

                # Check Links

                ```javascript
                function execute(context) {
                  return { type: 'text', data: 'ok' };
                }
                ```
                """);

        var skills = loader.loadSkills();

        assertThat(skills).hasSize(1);
        var skill = skills.getFirst();
        assertThat(skill.name()).isEqualTo("check-links");
        assertThat(skill.type()).isEqualTo(SkillSpec.SkillType.SCRIPT);
        assertThat(skill.trigger()).isEqualTo("/check-links");
        assertThat(skill.timeoutMillis()).isEqualTo(3000);
        assertThat(skill.tools()).containsExactly("readDocument");
        assertThat(skill.scriptSource()).contains("execute");
    }

    @Test
    @DisplayName("loads valid prompt skill without JS block")
    void loadSkills_validPrompt_loadsCorrectly() throws IOException {
        Path skillDir = tempDir.resolve("summarize");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: summarize
                description: Summarize a document
                type: prompt
                ---

                # Summarize
                Read the document and create a summary.
                """);

        var skills = loader.loadSkills();

        assertThat(skills).hasSize(1);
        assertThat(skills.getFirst().type()).isEqualTo(SkillSpec.SkillType.PROMPT);
        assertThat(skills.getFirst().scriptSource()).isNull();
    }

    @Test
    @DisplayName("skips skill without name")
    void loadSkills_noName_skips() throws IOException {
        Path skillDir = tempDir.resolve("bad");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: No name skill
                ---

                Some body.
                """);

        assertThat(loader.loadSkills()).isEmpty();
    }

    @Test
    @DisplayName("skips script skill without JS code block")
    void loadSkills_scriptNoJs_skips() throws IOException {
        Path skillDir = tempDir.resolve("broken");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: broken
                type: script
                ---

                No JavaScript block here.
                """);

        assertThat(loader.loadSkills()).isEmpty();
    }

    @Test
    @DisplayName("empty skills directory — returns empty list")
    void loadSkills_emptyDir_returnsEmpty() {
        assertThat(loader.loadSkills()).isEmpty();
    }
}

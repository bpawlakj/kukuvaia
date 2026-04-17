package ai.kukuvaia.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SkillSpec — skill definition record")
class SkillSpecTest {

    @Test
    @DisplayName("valid prompt skill — creates successfully")
    void constructor_validPrompt_creates() {
        var spec = new SkillSpec("my-skill", "desc", "/my-skill",
                SkillSpec.SkillType.PROMPT, null, 0, null, "body", null);

        assertThat(spec.name()).isEqualTo("my-skill");
        assertThat(spec.type()).isEqualTo(SkillSpec.SkillType.PROMPT);
        assertThat(spec.timeoutMillis()).isEqualTo(5000); // defaulted
        assertThat(spec.tools()).isEmpty(); // defaulted
    }

    @Test
    @DisplayName("null name — throws")
    void constructor_nullName_throws() {
        assertThatThrownBy(() -> new SkillSpec(null, "d", null, null, null, 0, null, "b", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("invalid name format — throws")
    void constructor_invalidName_throws() {
        assertThatThrownBy(() -> new SkillSpec("My Skill!", "d", null, null, null, 0, null, "b", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a-z0-9-]");
    }

    @Test
    @DisplayName("script type without source — throws")
    void constructor_scriptWithoutSource_throws() {
        assertThatThrownBy(() -> new SkillSpec("test", "d", null,
                SkillSpec.SkillType.SCRIPT, "javascript", 5000, List.of(), "body", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JavaScript code block");
    }

    @Test
    @DisplayName("valid script skill — creates successfully")
    void constructor_validScript_creates() {
        var spec = new SkillSpec("test", "d", null,
                SkillSpec.SkillType.SCRIPT, "javascript", 3000, List.of("readDocument"),
                "body", "function execute(ctx) { return 'ok'; }");

        assertThat(spec.type()).isEqualTo(SkillSpec.SkillType.SCRIPT);
        assertThat(spec.scriptSource()).contains("execute");
        assertThat(spec.timeoutMillis()).isEqualTo(3000);
    }
}

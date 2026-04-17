package ai.kukuvaia.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SkillRegistry — skill resolution by name and trigger")
class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        var spec1 = new SkillSpec("check-links", "Check links", "/check-links",
                SkillSpec.SkillType.SCRIPT, "javascript", 5000, List.of("readDocument"),
                "body", "function execute(ctx) { return 'ok'; }");
        var spec2 = new SkillSpec("summarize", "Summarize doc", null,
                SkillSpec.SkillType.PROMPT, null, 5000, List.of(), "body", null);

        var loader = mock(SkillsLoader.class);
        when(loader.loadSkills()).thenReturn(List.of(spec1, spec2));
        registry = new SkillRegistry(loader);
    }

    @Test
    @DisplayName("resolve — finds skill by exact name")
    void resolve_existingSkill_returnsPresent() {
        assertThat(registry.resolve("check-links")).isPresent();
        assertThat(registry.resolve("check-links").get().trigger()).isEqualTo("/check-links");
    }

    @Test
    @DisplayName("resolve — returns empty for unknown name")
    void resolve_unknownSkill_returnsEmpty() {
        assertThat(registry.resolve("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("resolveByTrigger — finds skill by trigger string")
    void resolveByTrigger_matchingTrigger_returnsSkill() {
        var result = registry.resolveByTrigger("/check-links");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("check-links");
    }

    @Test
    @DisplayName("resolveByTrigger — returns empty when no trigger matches")
    void resolveByTrigger_noMatch_returnsEmpty() {
        assertThat(registry.resolveByTrigger("/nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("resolveByTrigger — returns empty for skill without trigger")
    void resolveByTrigger_skillWithoutTrigger_notMatched() {
        assertThat(registry.resolveByTrigger("/summarize")).isEmpty();
    }

    @Test
    @DisplayName("allSkills — returns all registered skills")
    void allSkills_afterInit_containsAll() {
        assertThat(registry.allSkills()).hasSize(2);
        assertThat(registry.allSkills()).containsKeys("check-links", "summarize");
    }

    @Test
    @DisplayName("allSkills — returns unmodifiable map")
    void allSkills_modifyAttempt_throws() {
        var skills = registry.allSkills();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> skills.put("hack", null));
    }
}

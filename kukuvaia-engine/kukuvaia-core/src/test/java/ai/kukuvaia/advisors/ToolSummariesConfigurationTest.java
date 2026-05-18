package ai.kukuvaia.advisors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolSummariesConfiguration — built-in summaries for canonical MCP tools")
class ToolSummariesConfigurationTest {

    @Test
    @DisplayName("introspect_section_schema — extracts headline counts from JSON dump")
    void introspect_extractsCounts() {
        var fn = ToolSummariesConfiguration.introspectSectionSchemaSummary();
        String response = """
                {
                  "sectionTypes": ["a", "b", "c", "d", "e"],
                  "specs": [
                    {"name": "spec1", "type": "boolean"},
                    {"name": "spec2", "type": "boolean"},
                    {"name": "spec3", "type": "taxonomy"}
                  ]
                }
                """;
        String args = "{\"templateId\":\"tpl-123\"}";

        String summary = fn.summarise(args, response);

        assertThat(summary).contains("template tpl-123");
        assertThat(summary).contains("5 sectionTypes");
        assertThat(summary).contains("3 specs");
        assertThat(summary).contains("2 boolean");
        assertThat(summary).contains("1 taxonomy");
        assertThat(summary).contains("Re-call");
    }

    @Test
    @DisplayName("introspect_section_schema — malformed JSON returns null (caller falls back)")
    void introspect_malformed_returnsNull() {
        var fn = ToolSummariesConfiguration.introspectSectionSchemaSummary();
        assertThat(fn.summarise("{}", "not valid json {{{ broken")).isNull();
    }

    @Test
    @DisplayName("get_outline_sections — sample-titles list + total count")
    void getOutlineSections_sampleTitles() {
        var fn = ToolSummariesConfiguration.getOutlineSectionsSummary();
        String response = """
                {
                  "sections": [
                    {"id": "1", "title": "Intro"},
                    {"id": "2", "title": "Body"},
                    {"id": "3", "title": "Conclusion"}
                  ]
                }
                """;

        String summary = fn.summarise("{\"outlineId\":\"out-7\"}", response);

        assertThat(summary).contains("3 sections");
        assertThat(summary).contains("outline out-7");
        assertThat(summary).contains("\"Intro\"");
        assertThat(summary).contains("\"Body\"");
        assertThat(summary).contains("\"Conclusion\"");
    }

    @Test
    @DisplayName("get_outline_sections — accepts a bare array (no wrapper)")
    void getOutlineSections_bareArray() {
        var fn = ToolSummariesConfiguration.getOutlineSectionsSummary();
        String response = "[{\"title\":\"A\"},{\"title\":\"B\"}]";

        String summary = fn.summarise("{}", response);

        assertThat(summary).contains("2 sections");
    }

    @Test
    @DisplayName("get_outline_snapshot — keeps size marker + persona guidance pointer")
    void getOutlineSnapshot_alwaysElides() {
        var fn = ToolSummariesConfiguration.getOutlineSnapshotSummary();
        String big = "x".repeat(50_000);

        String summary = fn.summarise("{\"outlineId\":\"o\"}", big);

        assertThat(summary).contains("outline snapshot");
        assertThat(summary).contains("50000 chars");
        assertThat(summary).contains("persona guidance");
    }
}

package ai.kukuvaia.agent.daemon.def;

import ai.kukuvaia.agent.daemon.exec.JobKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DaemonJobParser — strict YAML schema")
class DaemonJobParserTest {

    private static final String FULL = """
            name: ci-failure-analysis
            description: Explain failed CI runs
            kind: specialist
            target: analyst
            input:
              fields: [repository, branch, status, error]
            prompt: "CI failed on {{fields.repository}}@{{ fields.branch }}: {{fields.error}}"
            triggers:
              - cron: "0 0 2 * * MON-FRI"
              - webhook: ci
              - pg-notify: new_content_items
              - manual: true
            provider: copilot
            limits: { timeout: 120s, max_tokens: 4000, max_tool_rounds: 6, max_actions: 20, token_budget: 20000 }
            overlap: { max_concurrent: 2 }
            notify: [log, webhook]
            enabled: true
            """;

    private static DaemonJobDefinition parse(String yaml) {
        return DaemonJobParser.parse(yaml, "test.yaml");
    }

    @Test
    @DisplayName("parses every field of the documented schema")
    void parsesFullDefinition() {
        DaemonJobDefinition d = parse(FULL);

        assertThat(d.name()).isEqualTo("ci-failure-analysis");
        assertThat(d.kind()).isEqualTo(JobKind.SPECIALIST);
        assertThat(d.target()).isEqualTo("analyst");
        assertThat(d.inputFields()).containsExactly("repository", "branch", "status", "error");
        assertThat(d.triggers()).containsExactly(new DaemonTrigger.Cron("0 0 2 * * MON-FRI"),
                new DaemonTrigger.Webhook("ci"), new DaemonTrigger.PgNotify("new_content_items"), new DaemonTrigger.Manual());
        assertThat(d.provider()).isEqualTo("copilot");
        assertThat(d.limits().timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(d.limits().maxTokens()).isEqualTo(4000);
        assertThat(d.limits().maxToolRounds()).isEqualTo(6);
        assertThat(d.overlap()).isEqualTo(Overlap.allow(2));
        assertThat(d.notifications()).containsExactly("log", "webhook");
        assertThat(d.enabled()).isTrue();
        assertThat(d.source()).isEqualTo("test.yaml");
    }

    @Test
    @DisplayName("defaults: manual trigger, skip overlap, log sink, enabled")
    void defaults() {
        DaemonJobDefinition d = parse("name: scan\nkind: java\ntarget: astralScan\n");

        assertThat(d.triggers()).containsExactly(new DaemonTrigger.Manual());
        assertThat(d.overlap()).isEqualTo(Overlap.skip());
        assertThat(d.notifications()).containsExactly("log");
        assertThat(d.enabled()).isTrue();
    }

    @Test
    @DisplayName("mcp target map becomes connection/tool")
    void mcpTarget() {
        assertThat(parse("name: snap\nkind: mcp\ntarget: { connection: sl-aipdlc, tool: check_access }\n").target())
                .isEqualTo("sl-aipdlc/check_access");
    }

    @Test
    @DisplayName("rejects: unknown keys (top-level and nested), bad cron, bad route, bad kind, bad timeout")
    void rejectsInvalid() {
        assertThatThrownBy(() -> parse("name: x1\nkind: java\ntarget: t\nshell: echo hi\n"))
                .hasMessageContaining("unknown key 'shell'");
        assertThatThrownBy(() -> parse("name: x1\nkind: java\ntarget: t\nlimits: { timeout: 5s, retries: 3 }\n"))
                .hasMessageContaining("unknown key 'limits.retries'");
        assertThatThrownBy(() -> parse("name: x1\nkind: java\ntarget: t\ntriggers: [ { cron: '* * *' } ]\n"))
                .hasMessageContaining("invalid cron");
        assertThatThrownBy(() -> parse("name: x1\nkind: java\ntarget: t\ntriggers: [ { webhook: '../admin' } ]\n"))
                .hasMessageContaining("webhook route");
        assertThatThrownBy(() -> parse("name: x1\nkind: shell\ntarget: t\n")).hasMessageContaining("kind must be");
        assertThatThrownBy(() -> parse("name: x1\nkind: java\ntarget: t\nlimits: { timeout: 9h }\n"))
                .hasMessageContaining("between 1s and 2h");
        assertThatThrownBy(() -> parse("name: Bad_Name\nkind: java\ntarget: t\n")).hasMessageContaining("name");
        assertThatThrownBy(() -> parse("not: [valid")).hasMessageContaining("not valid YAML");
    }

    @Test
    @DisplayName("prompt may only reference declared input fields — no raw payload interpolation")
    void promptPlaceholders() {
        assertThatThrownBy(() -> parse("""
                name: x1
                kind: specialist
                target: analyst
                input: { fields: [status] }
                prompt: "payload: {{event.payload}} {{fields.secret}}"
                """)).hasMessageContaining("{{event.payload}}").hasMessageContaining("{{fields.secret}}");
    }

    @Test
    @DisplayName("PromptRenderer substitutes declared fields only and leaves ${...} untouched")
    void renderer() {
        assertThat(PromptRenderer.render("CI {{fields.status}} on {{ fields.repo }} ${event.payload}",
                Map.of("status", "failed", "repo", "hub")))
                .isEqualTo("CI failed on hub ${event.payload}");
        assertThat(PromptRenderer.invalidPlaceholders("{{fields.a}} {{fields.b}}", Set.of("a"))).containsExactly("{{fields.b}}");
    }
}

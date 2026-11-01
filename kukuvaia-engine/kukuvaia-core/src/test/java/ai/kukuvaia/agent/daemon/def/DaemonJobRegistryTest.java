package ai.kukuvaia.agent.daemon.def;

import ai.kukuvaia.agent.daemon.exec.DaemonExecutor;
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome;
import ai.kukuvaia.agent.daemon.exec.JobInvocation;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DaemonJobRegistry — validation, precedence, indexes")
class DaemonJobRegistryTest {

    private static DaemonExecutor executor(JobKind kind, String validTarget) {
        return new DaemonExecutor() {
            @Override
            public JobKind kind() {
                return kind;
            }

            @Override
            public ExecutionOutcome execute(JobInvocation invocation, JobLimits limits) {
                return ExecutionOutcome.ofText("x");
            }

            @Override
            public Optional<String> validate(DaemonJobDefinition definition) {
                return definition.target().equals(validTarget) ? Optional.empty() : Optional.of("unknown target");
            }
        };
    }

    private final List<DaemonExecutor> executors =
            List.of(executor(JobKind.JAVA, "scan"), executor(JobKind.SPECIALIST, "analyst"));

    @Test
    @DisplayName("invalid files, unknown targets and unavailable kinds are rejected; valid ones load")
    void rejectsBadKeepsGood() {
        var registry = new DaemonJobRegistry(executors, List.of(
                Map.entry("a.yaml", "name: scan-job\nkind: java\ntarget: scan\ntriggers: [ { webhook: scan } ]\n"),
                Map.entry("b.yaml", "name: broken\nkind: java\n"),
                Map.entry("c.yaml", "name: ghost\nkind: java\ntarget: nope\n"),
                Map.entry("d.yaml", "name: remote\nkind: a2a\ntarget: { connection: x, agent: y }\n")));

        assertThat(registry.all()).extracting(DaemonJobDefinition::name).containsExactly("scan-job");
        assertThat(registry.rejected()).hasSize(3);
        assertThat(String.join("\n", registry.rejected()))
                .contains("b.yaml").contains("unknown target").contains("'a2a' is not available");
        assertThat(registry.byWebhookRoute("scan")).map(DaemonJobDefinition::name).contains("scan-job");
    }

    @Test
    @DisplayName("a later source (project) replaces a built-in with the same name")
    void projectOverridesBuiltIn() {
        var registry = new DaemonJobRegistry(executors, List.of(
                Map.entry("classpath:daemon/scan.yaml", "name: scan-job\nkind: java\ntarget: scan\ndescription: builtin\n"),
                Map.entry(".kukuvaia/daemon/scan.yaml", "name: scan-job\nkind: java\ntarget: scan\ndescription: project\n")));

        assertThat(registry.find("scan-job")).map(DaemonJobDefinition::description).contains("project");
    }

    @Test
    @DisplayName("two jobs cannot claim the same webhook route")
    void webhookRouteClash() {
        var registry = new DaemonJobRegistry(executors, List.of(
                Map.entry("a.yaml", "name: one\nkind: java\ntarget: scan\ntriggers: [ { webhook: ci } ]\n"),
                Map.entry("b.yaml", "name: two\nkind: java\ntarget: scan\ntriggers: [ { webhook: ci } ]\n")));

        assertThat(registry.all()).extracting(DaemonJobDefinition::name).containsExactly("one");
        assertThat(registry.rejected()).singleElement().asString().contains("already used by job 'one'");
    }

    @Test
    @DisplayName("PG NOTIFY channels are indexed per job")
    void pgNotifyIndex() {
        var registry = new DaemonJobRegistry(executors, List.of(
                Map.entry("a.yaml", "name: one\nkind: java\ntarget: scan\ntriggers: [ { pg-notify: content_changed } ]\n")));

        assertThat(registry.byPgNotifyChannel()).containsOnlyKeys("content_changed");
    }

    @Test
    @DisplayName("project jobs are only read when explicitly enabled (server-controlled by default)")
    void projectJobsOptIn() throws Exception {
        var root = Files.createTempDirectory("kv");
        Files.createDirectories(root.resolve("daemon"));
        Files.writeString(root.resolve("daemon/p.yaml"), "name: p\nkind: java\ntarget: scan\n");

        assertThat(DaemonJobRegistry.loadSources(Optional.empty()))
                .noneMatch(e -> e.getKey().startsWith(".kukuvaia"));
        assertThat(DaemonJobRegistry.loadSources(Optional.of(root)))
                .anyMatch(e -> e.getKey().equals(".kukuvaia/daemon/p.yaml"));
    }
}

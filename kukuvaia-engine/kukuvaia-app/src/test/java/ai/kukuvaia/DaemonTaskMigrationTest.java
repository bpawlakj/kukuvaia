package ai.kukuvaia;

import ai.kukuvaia.agent.daemon.DaemonBudgetGuard;
import ai.kukuvaia.agent.daemon.DaemonScheduleGuard;
import ai.kukuvaia.agent.daemon.NotificationSanitizer;
import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonRuntime;
import ai.kukuvaia.agent.daemon.DaemonTaskStore;
import ai.kukuvaia.agent.daemon.exec.SpecialistExecutor;
import ai.kukuvaia.security.ErrorSanitizer;
import java.util.List;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentResult;
import ai.kukuvaia.api.webhook.WebhookController;
import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.security.PayloadSanitizer;
import ai.kukuvaia.security.WebhookAuthFilter;
import ai.kukuvaia.security.WebhookRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against a disposable pgvector PostgreSQL database; no live provider calls are made. */
@EnabledIfEnvironmentVariable(named = "KUKUVAIA_TEST_DB_URL", matches = ".+")
class DaemonTaskMigrationTest {

    @Test
    void freshMigrations_authenticatedWebhookPersistsDaemonExecution() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("KUKUVAIA_TEST_DB_URL"), "postgres", "");
        var flyway = Flyway.configure().dataSource(dataSource).schemas("kukuvaia")
                .defaultSchema("kukuvaia").locations("classpath:db/migration").load();
        flyway.migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        var jdbc = new JdbcTemplate(dataSource);
        var budget = new DaemonBudgetGuard(jdbc);
        ReflectionTestUtils.setField(budget, "daemonEnabled", true);
        ReflectionTestUtils.setField(budget, "dailyTokenBudget", 100_000);
        ReflectionTestUtils.setField(budget, "alertThreshold", 0.8);
        var specialists = mock(SubAgentFactory.class);
        when(specialists.executeForResult(anyString(), eq("analyst"), eq(ExecutionContext.DAEMON), isNull(),
                eq(0), isNull(), anyInt(), anyInt()))
                .thenReturn(new SubAgentResult("Build failure analyzed", 100, 23, true));
        var properties = new DaemonProperties();
        var runtime = new DaemonRuntime(List.of(new SpecialistExecutor(specialists, properties)), budget,
                new DaemonScheduleGuard(), new DaemonTaskStore(jdbc), properties, new ErrorSanitizer(),
                new SimpleMeterRegistry());
        when(specialists.availableSpecialists()).thenReturn(java.util.Set.of("analyst"));
        // The real built-in YAML jobs (classpath:daemon/*.yaml) drive the webhook routes.
        var registry = new ai.kukuvaia.agent.daemon.def.DaemonJobRegistry(
                List.of(new SpecialistExecutor(specialists, properties)),
                ai.kukuvaia.agent.daemon.def.DaemonJobRegistry.loadSources(java.util.Optional.empty()));
        assertThat(registry.rejected()).isEmpty();
        var mvc = MockMvcBuilders.standaloneSetup(new WebhookController(registry, runtime,
                        new PayloadSanitizer(new ObjectMapper())))
                .addFilters(new WebhookRateLimiter(10, 100), new WebhookAuthFilter("", "test-token", true))
                .build();

        // Non-failure events are ignored by the job's input.match filter (no task row).
        mvc.perform(post("/api/webhooks/ci").contentType("application/json")
                        .header("Authorization", "Bearer test-token")
                        .content("{\"repo\":\"test\",\"status\":\"passed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("ignored"));

        // Asynchronous: 202 + task id immediately, the row completes in the background.
        mvc.perform(post("/api/webhooks/ci").contentType("application/json")
                        .header("Authorization", "Bearer test-token")
                        .content("{\"repo\":\"test\",\"status\":\"failed\",\"secret\":\"never-in-prompt\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        Long taskId = jdbc.queryForObject("SELECT id FROM kukuvaia_agent.daemon_tasks", Long.class);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> "COMPLETED".equals(
                jdbc.queryForObject("SELECT status FROM kukuvaia_agent.daemon_tasks WHERE id = ?", String.class, taskId)));
        assertThat(jdbc.queryForObject("SELECT prompt FROM kukuvaia_agent.daemon_tasks WHERE id = ?",
                String.class, taskId)).contains("repo: test").doesNotContain("never-in-prompt");
        assertThat(jdbc.queryForObject("SELECT result FROM kukuvaia_agent.daemon_tasks WHERE id = ?",
                String.class, taskId)).isEqualTo("Build failure analyzed");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL AND duration_ms >= 0 "
                + "FROM kukuvaia_agent.daemon_tasks WHERE id = ?", Boolean.class, taskId)).isTrue();
        // Token usage reported by the sub-agent is accounted automatically (no manual recordUsage).
        assertThat(jdbc.queryForObject("SELECT token_usage FROM kukuvaia_agent.daemon_tasks WHERE id = ?",
                Integer.class, taskId)).isEqualTo(123);
        assertThat(budget.getRemainingBudget()).isEqualTo(99_877);

        when(specialists.executeForResult(anyString(), eq("analyst"), eq(ExecutionContext.DAEMON), isNull(),
                eq(0), isNull(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("Synthetic specialist failure"));
        mvc.perform(post("/api/webhooks/ci").contentType("application/json")
                        .header("Authorization", "Bearer test-token")
                        .content("{\"repo\":\"test\",\"status\":\"failed\"}"))
                .andExpect(status().isAccepted());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                jdbc.queryForObject("SELECT COUNT(*) FROM kukuvaia_agent.daemon_tasks "
                        + "WHERE status = 'FAILED' AND completed_at IS NOT NULL", Integer.class) == 1);
        // Unknown routes are 404 (after authentication).
        mvc.perform(post("/api/webhooks/nope").contentType("application/json")
                        .header("Authorization", "Bearer test-token").content("{}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT to_regclass('kukuvaia.daemon_tasks') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void v24_upgradesExistingV23Rows() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(System.getenv("KUKUVAIA_TEST_DB_URL"), "postgres", ""));
        String db = "mig_v23_" + Long.toHexString(System.nanoTime());
        admin.execute("CREATE DATABASE " + db);
        String url = System.getenv("KUKUVAIA_TEST_DB_URL").replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
        var dataSource = new DriverManagerDataSource(url, "postgres", "");
        var jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).schemas("kukuvaia").defaultSchema("kukuvaia")
                .locations("classpath:db/migration").target("23").load().migrate();
        jdbc.update("INSERT INTO kukuvaia_agent.daemon_tasks (name, specialist_type, prompt, trigger_source, status, result) "
                + "VALUES ('ci', 'analyst', 'p', 'webhook:ci', 'COMPLETED', 'all good'), "
                + "('ci', 'analyst', 'p', 'webhook:ci', 'FAILED', 'Synthetic failure')");

        Flyway.configure().dataSource(dataSource).schemas("kukuvaia").defaultSchema("kukuvaia")
                .locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kukuvaia_agent.daemon_tasks "
                + "WHERE kind = 'specialist' AND target = 'analyst'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT result FROM kukuvaia_agent.daemon_tasks WHERE status = 'COMPLETED'",
                String.class)).isEqualTo("all good");
        assertThat(jdbc.queryForObject("SELECT error IS NOT NULL AND result IS NULL FROM kukuvaia_agent.daemon_tasks "
                + "WHERE status = 'FAILED'", Boolean.class)).as("V23 error text moved to error column").isTrue();
        // Retention: only finished rows older than the window are pruned.
        jdbc.update("INSERT INTO kukuvaia_agent.daemon_tasks (name, kind, target, trigger_source, status, created_at) "
                + "VALUES ('old', 'java', 't', 'cron', 'COMPLETED', now() - interval '40 days'), "
                + "('old-running', 'java', 't', 'cron', 'RUNNING', now() - interval '40 days')");
        assertThat(new DaemonTaskStore(jdbc).pruneOlderThan(30)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kukuvaia_agent.daemon_tasks WHERE name = 'old-running'",
                Integer.class)).isEqualTo(1);
        // Restart recovery: rows of a previous process are cancelled, interrupted LLM runs charged.
        jdbc.update("INSERT INTO kukuvaia_agent.daemon_tasks (name, kind, target, trigger_source, status, created_at) "
                + "VALUES ('stale-llm', 'specialist', 'analyst', 'cron', 'RUNNING', now() - interval '1 hour'), "
                + "('stale-q', 'java', 't', 'cron', 'QUEUED', now() - interval '1 hour')");
        // 3 = the two rows above + 'old-running' kept by the retention check (also from a previous process).
        assertThat(new DaemonTaskStore(jdbc).cancelInterrupted(java.time.Instant.now().minusSeconds(60), 20_000)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT token_usage FROM kukuvaia_agent.daemon_tasks WHERE name = 'stale-llm'",
                Integer.class)).isEqualTo(20_000);
        assertThat(jdbc.queryForObject("SELECT status || '|' || error FROM kukuvaia_agent.daemon_tasks WHERE name = 'stale-q'",
                String.class)).isEqualTo("CANCELLED|interrupted by restart (was QUEUED)");
        // New lifecycle states are accepted; unknown ones are not.
        jdbc.update("INSERT INTO kukuvaia_agent.daemon_tasks (name, kind, target, trigger_source, status) "
                + "VALUES ('scan', 'java', 'astralScan', 'cron', 'QUEUED')");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO kukuvaia_agent.daemon_tasks (name, kind, target, trigger_source, status) "
                        + "VALUES ('x', 'java', 't', 'cron', 'PENDING')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}

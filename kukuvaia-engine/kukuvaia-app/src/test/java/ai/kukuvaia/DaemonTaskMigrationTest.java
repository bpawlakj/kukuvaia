package ai.kukuvaia;

import ai.kukuvaia.agent.daemon.DaemonAgentService;
import ai.kukuvaia.agent.daemon.DaemonBudgetGuard;
import ai.kukuvaia.agent.daemon.DaemonScheduleGuard;
import ai.kukuvaia.agent.daemon.NotificationSanitizer;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
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
        when(specialists.execute(anyString(), eq("analyst"), eq(ExecutionContext.DAEMON), isNull()))
                .thenReturn("Build failure analyzed");
        var daemon = new DaemonAgentService(specialists, budget, new DaemonScheduleGuard(),
                new NotificationSanitizer(), jdbc, new SimpleMeterRegistry());
        var mvc = MockMvcBuilders.standaloneSetup(new WebhookController(daemon,
                        new PayloadSanitizer(new ObjectMapper())))
                .addFilters(new WebhookRateLimiter(10, 100), new WebhookAuthFilter("", "test-token", true))
                .build();

        mvc.perform(post("/api/webhooks/ci").contentType("application/json")
                        .header("Authorization", "Bearer test-token")
                        .content("{\"repo\":\"test\",\"status\":\"failed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        Long taskId = jdbc.queryForObject("SELECT id FROM kukuvaia_agent.daemon_tasks", Long.class);
        assertThat(jdbc.queryForObject("SELECT result FROM kukuvaia_agent.daemon_tasks WHERE id = ?",
                String.class, taskId)).isEqualTo("Build failure analyzed");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL AND duration_ms >= 0 "
                + "FROM kukuvaia_agent.daemon_tasks WHERE id = ?", Boolean.class, taskId)).isTrue();
        budget.recordUsage(taskId, 123);
        assertThat(budget.getRemainingBudget()).isEqualTo(99_877);

        when(specialists.execute(anyString(), eq("analyst"), eq(ExecutionContext.DAEMON), isNull()))
                .thenThrow(new IllegalStateException("Synthetic specialist failure"));
        mvc.perform(post("/api/webhooks/ci").contentType("application/json")
                        .header("Authorization", "Bearer test-token")
                        .content("{\"repo\":\"test\",\"status\":\"failed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kukuvaia_agent.daemon_tasks "
                + "WHERE status = 'FAILED' AND completed_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_regclass('kukuvaia.daemon_tasks') IS NULL", Boolean.class))
                .isTrue();
    }
}

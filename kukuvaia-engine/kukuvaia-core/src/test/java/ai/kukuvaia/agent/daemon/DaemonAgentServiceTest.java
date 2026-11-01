package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentGuard;
import ai.kukuvaia.agent.subagent.SubAgentResult;
import ai.kukuvaia.agent.subagent.SubAgentSpec;
import ai.kukuvaia.provider.model.ExecutionContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DaemonAgentService — token accounting and daemon tool allowlist")
class DaemonAgentServiceTest {

    private static final long TASK_ID = 42L;

    private final SubAgentFactory factory = mock(SubAgentFactory.class);
    private final DaemonBudgetGuard budget = mock(DaemonBudgetGuard.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DaemonProperties properties = new DaemonProperties();
    private DaemonAgentService service;

    private static final SubAgentSpec ANALYST = new SubAgentSpec("analyst", "d", null, null, "p",
            List.of("read_file", "save_memory"), null, 4096, 5, 0.1, 60);

    @BeforeEach
    void setUp() {
        when(budget.canExecute()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any())).thenReturn(TASK_ID);
        when(factory.spec("analyst")).thenReturn(Optional.of(ANALYST));
        service = new DaemonAgentService(factory, budget, new DaemonScheduleGuard(),
                new NotificationSanitizer(), jdbc, new SimpleMeterRegistry(), properties);
    }

    @Test
    @DisplayName("reported usage is recorded against the daily budget and returned")
    void reportedUsage_recorded() {
        when(factory.executeForResult(anyString(), eq("analyst"), eq(ExecutionContext.DAEMON), isNull(), eq(0), isNull()))
                .thenReturn(new SubAgentResult("ok", 100, 23, true));

        DaemonTaskResult result = service.execute("t", "analyst", "prompt", "manual", null);

        assertThat(result.status()).isEqualTo(DaemonTaskStatus.COMPLETED);
        assertThat(result.tokenUsage()).isEqualTo(123);
        verify(budget).recordUsage(TASK_ID, 123);
    }

    @Test
    @DisplayName("missing usage charges the specialist's max_tokens (fail closed)")
    void missingUsage_chargesMaxTokens() {
        when(factory.executeForResult(anyString(), eq("analyst"), any(), any(), anyInt(), any()))
                .thenReturn(new SubAgentResult("ok", 0, 0, false));

        DaemonTaskResult result = service.execute("t", "analyst", "prompt", "manual", null);

        assertThat(result.tokenUsage()).isEqualTo(4096);
        verify(budget).recordUsage(TASK_ID, 4096);
    }

    @Test
    @DisplayName("a timed-out run is charged max_tokens; other failures are not charged")
    void failures_charging() {
        when(factory.executeForResult(anyString(), eq("analyst"), any(), any(), anyInt(), any()))
                .thenThrow(new SubAgentFactory.SubAgentTimeoutException("analyst", 60));
        assertThat(service.execute("t1", "analyst", "p", "manual", null).status()).isEqualTo(DaemonTaskStatus.FAILED);
        verify(budget).recordUsage(TASK_ID, 4096);

        DaemonBudgetGuard budget2 = mock(DaemonBudgetGuard.class);
        when(budget2.canExecute()).thenReturn(true);
        var service2 = new DaemonAgentService(factory, budget2, new DaemonScheduleGuard(),
                new NotificationSanitizer(), jdbc, new SimpleMeterRegistry(), properties);
        when(factory.executeForResult(anyString(), eq("analyst"), any(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("boom"));
        assertThat(service2.execute("t2", "analyst", "p", "manual", null).status()).isEqualTo(DaemonTaskStatus.FAILED);
        verify(budget2, never()).recordUsage(anyLong(), anyInt());
    }

    @Test
    @DisplayName("budget exhausted: sub-agent is never called")
    void budgetExhausted_noCall() {
        when(budget.canExecute()).thenReturn(false);

        assertThat(service.execute("t", "analyst", "p", "manual", null).status()).isEqualTo(DaemonTaskStatus.FAILED);
        verify(factory, never()).executeForResult(anyString(), anyString(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("daemon allowlist is passed as the tool scope; empty allowlist means no extra restriction (null)")
    void allowlist_passedAsScope() {
        when(factory.executeForResult(anyString(), anyString(), any(), any(), anyInt(), any()))
                .thenReturn(new SubAgentResult("ok", 1, 1, true));

        service.execute("t", "analyst", "p", "manual", null);
        verify(factory).executeForResult("p", "analyst", ExecutionContext.DAEMON, null, 0, null);

        properties.setAllowedTools(List.of("read_file"));
        service.execute("t", "analyst", "p", "manual", null);
        verify(factory).executeForResult("p", "analyst", ExecutionContext.DAEMON, null, 0, List.of("read_file"));
    }

    @Test
    @DisplayName("allowlist intersection strips tools the daemon must not use (e.g. save_memory)")
    void allowlist_intersectionStripsSaveMemory() {
        properties.setAllowedTools(List.of("read_file"));
        var guard = new SubAgentGuard(3);

        assertThat(guard.filterTools(ANALYST.tools(), properties.toolScopeOrNull()))
                .containsExactly("read_file");
        assertThat(guard.filterTools(ANALYST.tools(), new DaemonProperties().toolScopeOrNull()))
                .containsExactlyInAnyOrder("read_file", "save_memory");
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}

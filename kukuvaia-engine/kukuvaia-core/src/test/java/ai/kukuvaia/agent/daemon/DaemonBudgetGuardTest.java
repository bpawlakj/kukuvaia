package ai.kukuvaia.agent.daemon;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DaemonBudgetGuardTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private DaemonBudgetGuard guard() {
        var guard = new DaemonBudgetGuard(jdbc);
        ReflectionTestUtils.setField(guard, "dailyTokenBudget", 500);
        ReflectionTestUtils.setField(guard, "daemonEnabled", true);
        ReflectionTestUtils.setField(guard, "alertThreshold", 0.8);
        return guard;
    }

    @Test
    void failedUsageWriteKeepsBudgetUnavailableUntilPersistenceRecovers() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
        when(jdbc.update(anyString(), (Object) any(), (Object) any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        var guard = guard();
        assertThat(guard.tryReserve(500)).isTrue();

        assertThatThrownBy(() -> guard.settle(1, 500, 500)).isInstanceOf(IllegalStateException.class);
        assertThat(guard.reservedTokens()).isEqualTo(500);
        assertThat(guard.tryReserve(1)).isFalse();

        doReturn(1).when(jdbc).update(anyString(), (Object) any(), (Object) any());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(500);
        assertThat(guard.tryReserve(1)).isFalse();
        assertThat(guard.reservedTokens()).as("persisted debt is released exactly once").isZero();
    }

    @Test
    void loggingQueryFailureDoesNotUndoASuccessfulDurableWrite() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0).thenThrow(new IllegalStateException("logging read failed"));
        when(jdbc.update(anyString(), (Object) any(), (Object) any())).thenReturn(1);
        var guard = guard();
        assertThat(guard.tryReserve(500)).isTrue();

        assertThatCode(() -> guard.settle(1, 500, 200)).doesNotThrowAnyException();
        assertThat(guard.reservedTokens()).isZero();
    }

    @Test
    void missingTaskCannotSilentlyConsumeAReservation() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
        when(jdbc.update(anyString(), (Object) any(), (Object) any())).thenReturn(0);
        var guard = guard();
        assertThat(guard.tryReserve(500)).isTrue();

        assertThatThrownBy(() -> guard.settle(99, 500, 500)).isInstanceOf(IllegalStateException.class);
        assertThat(guard.reservedTokens()).isEqualTo(500);
    }

    @Test
    void conservativeChargeSurvivesGuardRestartAndFinalWriteFailure() {
        var ledger = new java.util.concurrent.atomic.AtomicInteger();
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenAnswer(invocation -> ledger.get());
        when(jdbc.update(anyString(), (Object) any(), (Object) any())).thenAnswer(invocation -> {
            if (unavailable.get()) throw new IllegalStateException("database unavailable");
            ledger.set(invocation.getArgument(1));
            return 1;
        });
        var original = guard();
        assertThat(original.tryReserve(500)).isTrue();
        original.precharge(1, 500, 500);
        assertThat(original.reservedTokens()).isZero();
        assertThat(ledger).hasValue(500);

        unavailable.set(true);
        assertThatThrownBy(() -> original.settle(1, 0, 200)).isInstanceOf(IllegalStateException.class);
        assertThat(guard().tryReserve(1)).as("a new process still sees the durable conservative charge").isFalse();

        unavailable.set(false);
        assertThat(original.tryReserve(300)).as("retry restores the actual charge without double counting").isTrue();
        assertThat(ledger).hasValue(200);
        assertThat(original.reservedTokens()).isEqualTo(300);
    }
}

package ai.kukuvaia.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionEscalationService — one-shot escalation flag")
class SessionEscalationServiceTest {

    private final SessionEscalationService service = new SessionEscalationService();

    @Test
    @DisplayName("mark then consume returns true once, then false")
    void mark_consume_oneShot() {
        service.markEscalate("session-1");

        assertThat(service.consumeEscalate("session-1")).isTrue();
        assertThat(service.consumeEscalate("session-1")).isFalse();
    }

    @Test
    @DisplayName("consume without mark returns false")
    void consume_unmarked_returnsFalse() {
        assertThat(service.consumeEscalate("unknown-session")).isFalse();
    }

    @Test
    @DisplayName("marks are per-session isolated")
    void marks_areIsolatedPerSession() {
        service.markEscalate("session-a");

        assertThat(service.consumeEscalate("session-b")).isFalse();
        assertThat(service.consumeEscalate("session-a")).isTrue();
    }

    @Test
    @DisplayName("null sessionId is a no-op")
    void nullSessionId_noop() {
        service.markEscalate(null);
        assertThat(service.consumeEscalate(null)).isFalse();
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    @DisplayName("re-mark after consume re-arms the flag")
    void remark_afterConsume_reArms() {
        service.markEscalate("session-1");
        service.consumeEscalate("session-1");
        service.markEscalate("session-1");

        assertThat(service.consumeEscalate("session-1")).isTrue();
    }
}

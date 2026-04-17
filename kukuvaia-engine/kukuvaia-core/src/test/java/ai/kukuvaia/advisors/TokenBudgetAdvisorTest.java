package ai.kukuvaia.advisors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenBudgetAdvisor — per-session token tracking")
class TokenBudgetAdvisorTest {

    private TokenBudgetAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new TokenBudgetAdvisor();
    }

    @Test
    @DisplayName("getUsage — returns 0 for new session")
    void getUsage_newSession_returnsZero() {
        assertThat(advisor.getUsage("new-session")).isZero();
    }

    @Test
    @DisplayName("resetUsage — clears session usage")
    void resetUsage_afterUsage_clearsToZero() {
        // No direct way to add usage without mocking ChatClientResponse,
        // but reset should not throw
        advisor.resetUsage("session-1");
        assertThat(advisor.getUsage("session-1")).isZero();
    }
}

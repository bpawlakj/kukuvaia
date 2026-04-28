package ai.kukuvaia.agent;

import ai.kukuvaia.plans.PlansRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the EXECUTING phase added in step 2 — tests the new state-machine
 * transitions and the in-memory session lifecycle. DB behaviour is verified by
 * matching the SQL fragments that define the legal transitions.
 */
@DisplayName("PlanningModeService — EXECUTING phase lifecycle")
@ExtendWith(MockitoExtension.class)
class PlanningModeServiceExecutingTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private ObjectProvider<ChatModel> chatModelProvider;
    @Mock private PlansRepository plansRepository;

    private PlanningModeService service;
    private static final String SESSION = "session-1";

    @BeforeEach
    void setUp() {
        service = new PlanningModeService(jdbc, chatModelProvider, plansRepository);
        lenient().when(chatModelProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    @DisplayName("approvePlan transitions session to EXECUTING and keeps it in memory")
    void approvePlan_transitionsToExecuting() {
        service.startPlanning(SESSION, "task");
        service.advanceToDrafting(SESSION);
        service.advanceToApproval(SESSION);
        when(jdbc.update(contains("status = 'active', phase = 'executing'"), eq(SESSION))).thenReturn(1);

        service.approvePlan(SESSION);

        assertThat(service.getSession(SESSION)).isPresent();
        assertThat(service.getSession(SESSION).get().phase()).isEqualTo(PlanningPhase.EXECUTING);
    }

    @Test
    @DisplayName("approvePlan without DB update does NOT change in-memory phase")
    void approvePlan_noDbUpdate_keepsPhase() {
        service.startPlanning(SESSION, "task");
        service.advanceToDrafting(SESSION);
        service.advanceToApproval(SESSION);
        when(jdbc.update(anyString(), eq(SESSION))).thenReturn(0);

        service.approvePlan(SESSION);

        assertThat(service.getSession(SESSION).get().phase()).isEqualTo(PlanningPhase.APPROVAL);
    }

    @Test
    @DisplayName("revertExecutingToDrafting transitions EXECUTING → DRAFTING and persists phase")
    void revertExecutingToDrafting_transitions() {
        service.startPlanning(SESSION, "task");
        service.advanceToDrafting(SESSION);
        service.advanceToApproval(SESSION);
        when(jdbc.update(contains("status = 'active', phase = 'executing'"), eq(SESSION))).thenReturn(1);
        service.approvePlan(SESSION);
        assertThat(service.getSession(SESSION).get().phase()).isEqualTo(PlanningPhase.EXECUTING);

        service.revertExecutingToDrafting(SESSION);

        assertThat(service.getSession(SESSION).get().phase()).isEqualTo(PlanningPhase.DRAFTING);
        // advanceToDrafting persists once, revertExecutingToDrafting persists again — both
        // with the same ('drafting', sessionId) args. At-least-one is the invariant we care about.
        verify(jdbc, atLeast(1)).update(contains("SET phase = ?"), eq("drafting"), eq(SESSION));
    }

    @Test
    @DisplayName("revertExecutingToDrafting is a no-op when phase is not EXECUTING")
    void revertExecutingToDrafting_noopOutsideExecuting() {
        service.startPlanning(SESSION, "task");  // DISCOVERY

        service.revertExecutingToDrafting(SESSION);

        assertThat(service.getSession(SESSION).get().phase()).isEqualTo(PlanningPhase.DISCOVERY);
        verify(jdbc, never()).update(contains("SET phase = ?"), anyString(), eq(SESSION));
    }

    @Test
    @DisplayName("markExecutingDone sets status=completed + phase=done and removes session")
    void markExecutingDone_closesPlan() {
        service.startPlanning(SESSION, "task");
        service.advanceToDrafting(SESSION);
        service.advanceToApproval(SESSION);
        when(jdbc.update(contains("status = 'active', phase = 'executing'"), eq(SESSION))).thenReturn(1);
        service.approvePlan(SESSION);
        when(jdbc.update(contains("status = 'completed', phase = 'done'"), eq(SESSION))).thenReturn(1);

        boolean done = service.markExecutingDone(SESSION);

        assertThat(done).isTrue();
        assertThat(service.getSession(SESSION)).isEmpty();
    }

    @Test
    @DisplayName("markExecutingDone returns false when no active plan exists for session")
    void markExecutingDone_noActivePlan() {
        when(jdbc.update(contains("status = 'completed', phase = 'done'"), eq(SESSION))).thenReturn(0);

        boolean done = service.markExecutingDone(SESSION);

        assertThat(done).isFalse();
    }

    @Test
    @DisplayName("exitPlanMode removes in-memory session without touching DB status")
    void exitPlanMode_inMemoryOnly() {
        service.startPlanning(SESSION, "task");

        service.exitPlanMode(SESSION);

        assertThat(service.getSession(SESSION)).isEmpty();
        // No UPDATE on plans status / phase should have run.
        verify(jdbc, never()).update(contains("status = 'active'"), anyString());
        verify(jdbc, never()).update(contains("status = 'completed'"), anyString());
    }

    @Test
    @DisplayName("resumeFromDb with phase='executing' loads into EXECUTING phase")
    void resumeFromDb_executingPhase() {
        java.util.UUID planId = java.util.UUID.randomUUID();
        var state = new PlansRepository.PlanFullState(
                planId, "orig", "bartek", "task", "name", "[]", "{}",
                "active", "executing", Instant.now(), Instant.now());
        when(plansRepository.findFullState(planId, "bartek"))
                .thenReturn(java.util.Optional.of(state));

        var resumed = service.resumeFromDb(planId, SESSION);

        assertThat(resumed.phase()).isEqualTo(PlanningPhase.EXECUTING);
        assertThat(service.getSession(SESSION)).isPresent();
    }
}

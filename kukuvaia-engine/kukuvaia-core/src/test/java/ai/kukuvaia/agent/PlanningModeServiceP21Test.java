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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PlanningModeService — P21 resume / combine / abandon")
@ExtendWith(MockitoExtension.class)
class PlanningModeServiceP21Test {

    @Mock private JdbcTemplate jdbc;
    @Mock private ObjectProvider<ChatModel> chatModelProvider;
    @Mock private PlansRepository plansRepository;

    private PlanningModeService service;

    @BeforeEach
    void setUp() {
        service = new PlanningModeService(jdbc, chatModelProvider, plansRepository);
        lenient().when(chatModelProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    @DisplayName("resumeFromDb rehydrates session with phase + facts")
    void resumeFromDb_rehydrates() {
        UUID planId = UUID.randomUUID();
        String factsJson = "{\"knownFacts\":[\"dest: Crete\"],\"excludedOptions\":[\"flights via LHR\"],"
                + "\"remainingGaps\":[\"dates\"],\"ambiguities\":[]}";
        var state = new PlansRepository.PlanFullState(
                planId, "orig-session", "bartek", "Wyjazd Kreta lipiec", "Kreta",
                "[]", factsJson, "draft", "drafting",
                Instant.now(), Instant.now());
        when(plansRepository.findFullState(planId, "bartek")).thenReturn(Optional.of(state));

        var resumed = service.resumeFromDb(planId, "new-session");

        assertThat(resumed.task()).isEqualTo("Wyjazd Kreta lipiec");
        assertThat(resumed.phase()).isEqualTo(PlanningPhase.DRAFTING);
        assertThat(resumed.facts().knownFacts()).containsExactly("dest: Crete");
        assertThat(resumed.facts().excludedOptions()).containsExactly("flights via LHR");
        assertThat(service.getSession("new-session")).isPresent();
        // Cross-session — session_id on row is updated to new session.
        verify(jdbc).update(anyString(), eq("new-session"), eq(planId));
    }

    @Test
    @DisplayName("resumeFromDb rejects abandoned / done plans")
    void resumeFromDb_rejectsTerminal() {
        UUID planId = UUID.randomUUID();
        var state = new PlansRepository.PlanFullState(
                planId, "s", "bartek", "t", "n", "[]", "{}", "abandoned", "abandoned",
                Instant.now(), Instant.now());
        when(plansRepository.findFullState(planId, "bartek")).thenReturn(Optional.of(state));

        assertThatThrownBy(() -> service.resumeFromDb(planId, "s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("abandoned");
    }

    @Test
    @DisplayName("resumeFromDb throws when plan does not exist for user")
    void resumeFromDb_notFound() {
        UUID planId = UUID.randomUUID();
        when(plansRepository.findFullState(planId, "bartek")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resumeFromDb(planId, "s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plan not found");
    }

    @Test
    @DisplayName("combinePlans merges facts, creates plan + links, seeds in-memory session")
    void combinePlans_merges() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID newId = UUID.randomUUID();

        when(plansRepository.allBelongToUser(List.of(p1, p2), "bartek")).thenReturn(true);
        when(plansRepository.findFullState(p1, "bartek")).thenReturn(Optional.of(state(p1,
                "{\"knownFacts\":[\"A\",\"B\"],\"excludedOptions\":[\"X\"],\"remainingGaps\":[\"g1\"],\"ambiguities\":[]}")));
        when(plansRepository.findFullState(p2, "bartek")).thenReturn(Optional.of(state(p2,
                "{\"knownFacts\":[\"B\",\"C\"],\"excludedOptions\":[\"Y\"],\"remainingGaps\":[\"g2\"],\"ambiguities\":[\"q?\"]}")));
        when(plansRepository.createPending(anyString(), anyString(), anyString(), anyString(), eq("discovery"), anyString()))
                .thenReturn(newId);

        var result = service.combinePlans(List.of(p1, p2), "Joint trip", "current-session");

        assertThat(result.newPlanId()).isEqualTo(newId);
        // Naive dedup: A,B,C
        assertThat(result.mergedFacts().knownFacts()).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(result.mergedFacts().excludedOptions()).containsExactlyInAnyOrder("X", "Y");
        // Ambiguities reset to empty — re-asked in new DISCOVERY.
        assertThat(result.mergedFacts().ambiguities()).isEmpty();
        // Links created (2 parents × 1 call each)
        verify(plansRepository).createLink(newId, p1, "combines", null);
        verify(plansRepository).createLink(newId, p2, "combines", null);
        // In-memory session active
        assertThat(service.getSession("current-session")).isPresent();
        assertThat(service.getSession("current-session").get().phase()).isEqualTo(PlanningPhase.DISCOVERY);
    }

    @Test
    @DisplayName("combinePlans refuses parents belonging to a different user")
    void combinePlans_refusesOtherUser() {
        UUID p1 = UUID.randomUUID();
        when(plansRepository.allBelongToUser(List.of(p1), "bartek")).thenReturn(false);

        assertThatThrownBy(() -> service.combinePlans(List.of(p1), "task", "s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not belong");

        verify(plansRepository, never()).createPending(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("combinePlans requires non-empty parents and task")
    void combinePlans_inputValidation() {
        assertThatThrownBy(() -> service.combinePlans(List.of(), "t", "s"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.combinePlans(null, "t", "s"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("abandonPlan issues UPDATE and returns affected rows")
    void abandonPlan() {
        UUID id = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        int affected = service.abandonPlan(id);
        assertThat(affected).isEqualTo(1);
        verify(jdbc).update(anyString(), eq(id));
    }

    private static PlansRepository.PlanFullState state(UUID id, String factsJson) {
        return new PlansRepository.PlanFullState(
                id, "s", "bartek", "task", "name", "[]", factsJson, "draft", "approval",
                Instant.now(), Instant.now());
    }
}

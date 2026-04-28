package ai.kukuvaia.tools;

import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.plans.PlanEntry;
import ai.kukuvaia.plans.PlansRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@link PlanRegistryTools#list_plans(String, Integer)} contract:
 * the tool MUST return full structured rows (with pre-computed English
 * labels) so the LLM can summarise them faithfully in text. It MUST NOT open
 * the interactive picker — that is reserved for the explicit {@code /plans}
 * slash command. The tool also protects against status-mislabelling
 * hallucinations by shipping a {@code statusLabel} per row so the model
 * never has to invent a translation.
 */
@DisplayName("PlanRegistryTools — plan registry natural-language entry points")
class PlanRegistryToolsTest {

    private PlansRepository plansRepository;
    private PlanningModeService planningModeService;
    private PlanRegistryTools tools;

    @BeforeEach
    void setUp() {
        plansRepository = mock(PlansRepository.class);
        planningModeService = mock(PlanningModeService.class);
        tools = new PlanRegistryTools(plansRepository, planningModeService);
        PlanningTools.setContext("session-42", "bartek");
    }

    @AfterEach
    void tearDown() {
        PlanningTools.clearContext();
    }

    @Test
    @DisplayName("list_plans — returns full rows with verbatim status + English statusLabel per plan")
    void listPlans_returnsFullRowsWithStatusLabels() {
        PlanEntry active = plan("krotki wyjazd do czech", "active", "executing");
        PlanEntry done = plan("Wakacje na Krecie", "completed", "done");
        when(plansRepository.findByUser(anyString(), any(), anyInt()))
                .thenReturn(List.of(active, done));

        Map<String, Object> result = tools.list_plans(null, null);

        assertThat(result)
                .containsEntry("count", 2)
                .containsEntry("statusFilter", "all")
                .containsKey("plans");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) result.get("plans");
        assertThat(plans).hasSize(2);

        // First row — active/executing plan. statusLabel must reflect that, not a hallucination.
        assertThat(plans.get(0))
                .containsEntry("status", "active")
                .containsEntry("phase", "executing")
                .containsEntry("statusLabel", "Active (in progress)")
                .containsEntry("name", "krotki wyjazd do czech");

        // Second row — completed plan. Must be labelled as completed, never reused from another row.
        assertThat(plans.get(1))
                .containsEntry("status", "completed")
                .containsEntry("phase", "done")
                .containsEntry("statusLabel", "Completed")
                .containsEntry("name", "Wakacje na Krecie");
    }

    @Test
    @DisplayName("list_plans — abandoned/draft plans get their own labels (never collapse to one label)")
    void listPlans_abandonedAndDraftLabels() {
        PlanEntry abandoned = plan("old czech trip", "abandoned", "executing");
        PlanEntry draft = plan("new trip idea", "draft", "drafting");
        when(plansRepository.findByUser(anyString(), any(), anyInt()))
                .thenReturn(List.of(abandoned, draft));

        Map<String, Object> result = tools.list_plans(null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) result.get("plans");
        assertThat(plans.get(0)).containsEntry("statusLabel", "Abandoned");
        assertThat(plans.get(1)).containsEntry("statusLabel", "Draft (awaiting approval)");
    }

    @Test
    @DisplayName("list_plans — normalises status filter to lowercase and forwards to the repository")
    void listPlans_normalisesFilter() {
        when(plansRepository.findByUser(anyString(), eq("completed"), anyInt()))
                .thenReturn(List.of());

        Map<String, Object> result = tools.list_plans("COMPLETED", null);

        assertThat(result)
                .containsEntry("count", 0)
                .containsEntry("statusFilter", "completed");
    }

    private static PlanEntry plan(String name, String status, String phase) {
        Instant now = Instant.now();
        return new PlanEntry(
                UUID.randomUUID(),
                "session-42",
                "bartek",
                name,
                name,
                status,
                phase,
                now,
                now,
                List.of());
    }
}

package ai.kukuvaia.tools;

import ai.kukuvaia.agent.PlanningModeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("PlanningTools — natural-language planning entry points")
class PlanningToolsTest {

    private JdbcTemplate jdbcTemplate;
    private PlanningModeService planningModeService;
    private PlanningTools tools;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        planningModeService = mock(PlanningModeService.class);
        tools = new PlanningTools(jdbcTemplate, planningModeService);
        PlanningTools.setContext("session-42", "bartek");
    }

    @AfterEach
    void tearDown() {
        PlanningTools.clearContext();
    }

    @Test
    @DisplayName("startPlanning — delegates to PlanningModeService with current session")
    void startPlanning_delegatesToService() {
        Map<String, Object> result = tools.startPlanning("zaplanujmy wyjazd na Krete");

        verify(planningModeService).startPlanning("session-42", "zaplanujmy wyjazd na Krete");
        assertThat(result)
                .containsEntry("ok", true)
                .containsEntry("phase", "DISCOVERY")
                .containsEntry("task", "zaplanujmy wyjazd na Krete");
    }

    @Test
    @DisplayName("startPlanning — rejects blank task and does not start a session")
    void startPlanning_blankTask_rejected() {
        Map<String, Object> result = tools.startPlanning("   ");

        verify(planningModeService, never()).startPlanning(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(result).containsEntry("ok", false).containsKey("error");
    }

    @Test
    @DisplayName("startPlanning — rejects null task and does not start a session")
    void startPlanning_nullTask_rejected() {
        Map<String, Object> result = tools.startPlanning(null);

        verify(planningModeService, never()).startPlanning(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(result).containsEntry("ok", false).containsKey("error");
    }

    @Test
    @DisplayName("createPlan — null task is rejected BEFORE any DB write (protects live plans)")
    void createPlan_nullTask_rejectedBeforeAnyDbWrite() {
        Map<String, Object> result = tools.createPlan(null, "[\"Step 1\"]");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
        verify(planningModeService, never()).advanceToApproval(anyString());
        assertThat(result).containsEntry("created", false).containsKey("error");
    }

    @Test
    @DisplayName("createPlan — blank task is rejected BEFORE any DB write")
    void createPlan_blankTask_rejectedBeforeAnyDbWrite() {
        Map<String, Object> result = tools.createPlan("   ", "[\"Step 1\"]");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
        verify(planningModeService, never()).advanceToApproval(anyString());
        assertThat(result).containsEntry("created", false).containsKey("error");
    }

    @Test
    @DisplayName("createPlan — null stepsJson is rejected BEFORE any DB write")
    void createPlan_nullSteps_rejectedBeforeAnyDbWrite() {
        Map<String, Object> result = tools.createPlan("valid task", null);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
        verify(planningModeService, never()).advanceToApproval(anyString());
        assertThat(result).containsEntry("created", false).containsKey("error");
    }

    @Test
    @DisplayName("createPlan — malformed stepsJson (not a JSON array) is rejected")
    void createPlan_malformedSteps_rejected() {
        Map<String, Object> result = tools.createPlan("valid task", "{\"not\": \"array\"}");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
        verify(planningModeService, never()).advanceToApproval(anyString());
        assertThat(result).containsEntry("created", false).containsKey("error");
    }

    @Test
    @DisplayName("createPlan — valid inputs: abandons ONLY drafts in same session, not active plans")
    void createPlan_validInputs_abandonsOnlyDraftsNeverActive() {
        tools.createPlan("wyjazd do czech", "[\"Step A\",\"Step B\"]");

        // The abandon SQL must target only drafts; must NOT use "status IN ('draft','active')"
        verify(jdbcTemplate).update(contains("status = 'draft'"), eq("session-42"));
        verify(jdbcTemplate, never()).update(contains("status IN ('draft', 'active')"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("status IN ('draft', 'active')"), any(Object.class));

        // Insert + abandon = 2 JDBC updates total
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
        verify(planningModeService).advanceToApproval("session-42");
    }
}

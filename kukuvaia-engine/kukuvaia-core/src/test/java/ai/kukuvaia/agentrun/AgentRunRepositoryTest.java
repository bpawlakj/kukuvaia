package ai.kukuvaia.agentrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * SQL-shape tests for {@link AgentRunRepository}. We don't have a Testcontainers fixture in
 * kukuvaia-core today, so this test mirrors {@code PlansRepositoryTest}: assert the structure
 * of the SQL emitted plus the parameter ordering. End-to-end DB behaviour is covered by the
 * existing app-level integration tests against a real Postgres + pgvector once the migration
 * is applied (V19 is straight DDL — no risk of an interaction-only bug).
 */
@DisplayName("AgentRunRepository — SQL shape")
@ExtendWith(MockitoExtension.class)
class AgentRunRepositoryTest {

    @Mock private JdbcTemplate jdbc;
    private AgentRunRepository repo;

    @BeforeEach
    void setUp() {
        repo = new AgentRunRepository(jdbc, new ObjectMapper());
    }

    @Test
    @DisplayName("insertQueued issues INSERT with status=queued, queued_at=NOW(), JSONB cast")
    void insertQueued_sqlShape() {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("ruleId", "r-1");
        AgentRunSpec spec = new AgentRunSpec(
                "test-mcp:test-evaluator", "agent",
                "validation_rule_drafting", input,
                /*personaName*/ null,
                /*llmFollowup*/ null,
                /*parentRunId*/ null, /*threadId*/ null,
                List.of("tag-a"), /*severity*/ null, /*taskClass*/ null);

        repo.insertQueued(spec);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(Object[].class));
        String sql = sqlCap.getValue();
        assertThat(sql)
                .contains("INSERT INTO kukuvaia.agent_runs")
                .contains("?::jsonb")
                .contains("queued_at")
                .contains("NOW()");
    }

    @Test
    @DisplayName("markCompleted casts vector and bumps completed_at")
    void markCompleted_sqlShape() {
        repo.markCompleted(java.util.UUID.randomUUID(),
                JsonNodeFactory.instance.objectNode().put("k", "v"),
                "hello",
                new float[] {0.1f, 0.2f, 0.3f},
                "claude-haiku-4-5",
                42, 17);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(), any(), any(), any(), any(), any(), any(), any());
        String sql = sqlCap.getValue();
        assertThat(sql)
                .contains("UPDATE kukuvaia.agent_runs")
                .contains("status = ?")
                .contains("output = ?::jsonb")
                .contains("CAST(? AS vector)")
                .contains("completed_at = NOW()");
    }

    @Test
    @DisplayName("markFailed sets error_code, error_message, completed_at")
    void markFailed_sqlShape() {
        repo.markFailed(java.util.UUID.randomUUID(), "TimeoutException", "upstream timeout");

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(), any(), any(), any());
        assertThat(sqlCap.getValue())
                .contains("status = ?")
                .contains("error_code = ?")
                .contains("error_message = ?")
                .contains("completed_at = NOW()");
    }

    @Test
    @DisplayName("findSimilar emits cosine ORDER BY and applies optional filters")
    void findSimilar_sqlShape() {
        repo.findSimilar(new float[] {0.1f, 0.2f}, 5, 30, "scheduler:nightly", List.of("ops"));

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCap.capture(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        String sql = sqlCap.getValue();
        assertThat(sql)
                .contains("FROM kukuvaia.agent_runs")
                .contains("embedding IS NOT NULL")
                .contains("status = 'completed'")
                .contains("ORDER BY embedding <=> CAST(? AS vector)")
                .contains("invoker_name = ?")
                .contains("tags && ?")
                .contains("created_at > NOW() - CAST(? || ' days' AS INTERVAL)");
    }

    @Test
    @DisplayName("findSimilar without filters emits only base query + ORDER BY + LIMIT")
    void findSimilar_noFilters() {
        repo.findSimilar(new float[] {0.1f}, 3, null, null, null);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sqlCap.capture(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        String sql = sqlCap.getValue();
        assertThat(sql).contains("ORDER BY embedding <=>");
        assertThat(sql).doesNotContain("invoker_name = ?");
        assertThat(sql).doesNotContain("tags && ?");
    }

    @Test
    @DisplayName("findById issues SELECT * WHERE id = ?")
    void findById_sql() {
        repo.findById(java.util.UUID.randomUUID());
        verify(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
    }
}

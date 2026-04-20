package ai.kukuvaia.plans;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PlansRepository — unit-level behavior (no real DB)")
@ExtendWith(MockitoExtension.class)
class PlansRepositoryTest {

    @Mock private JdbcTemplate jdbc;
    private PlansRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PlansRepository(jdbc);
    }

    @Test
    @DisplayName("findByUser with blank userId returns empty list without hitting DB")
    void findByUser_blankUserId_empty() {
        assertThat(repo.findByUser("", null, 10)).isEmpty();
        assertThat(repo.findByUser(null, null, 10)).isEmpty();
    }

    @Test
    @DisplayName("allBelongToUser false when list is empty or user missing")
    void allBelongToUser_edgeCases() {
        assertThat(repo.allBelongToUser(List.of(), "bartek")).isFalse();
        assertThat(repo.allBelongToUser(null, "bartek")).isFalse();
        assertThat(repo.allBelongToUser(List.of(UUID.randomUUID()), null)).isFalse();
    }

    @Test
    @DisplayName("createLink issues ON CONFLICT DO NOTHING insert")
    void createLink_insertsWithConflictClause() {
        UUID src = UUID.randomUUID();
        UUID tgt = UUID.randomUUID();
        repo.createLink(src, tgt, "combines", "note");

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(Object[].class));
        assertThat(sqlCap.getValue()).contains("ON CONFLICT");
    }

    @Test
    @DisplayName("updatePhase issues phase-only UPDATE with updated_at")
    void updatePhase_sql() {
        UUID planId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        int affected = repo.updatePhase(planId, "drafting");

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(Object[].class));
        assertThat(sqlCap.getValue())
                .contains("UPDATE kukuvaia.plans")
                .contains("phase = ?")
                .contains("updated_at = NOW()");
        assertThat(affected).isEqualTo(1);
    }

    @Test
    @DisplayName("updateDiscoveryFacts casts to jsonb")
    void updateDiscoveryFacts_castsJsonb() {
        UUID planId = UUID.randomUUID();
        repo.updateDiscoveryFacts(planId, "{\"knownFacts\":[]}");

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCap.capture(), any(Object[].class));
        assertThat(sqlCap.getValue()).contains("::jsonb");
    }
}

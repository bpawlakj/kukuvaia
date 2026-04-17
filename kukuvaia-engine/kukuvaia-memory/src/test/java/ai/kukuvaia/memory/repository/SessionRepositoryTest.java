package ai.kukuvaia.memory.repository;

import ai.kukuvaia.memory.model.KukuvaiaSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        sessionRepository = new SessionRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("save executes insert with parameterized query against kukuvaia.sessions")
    void save_validSession_executesInsert() {
        sessionRepository.save("s1", "u1", "My Session", "{}");

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("s1"), eq("u1"), eq("My Session"), eq("{}"));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("kukuvaia.sessions");
        assertThat(sql).doesNotContain("kukuvaia_agent");
    }

    @Test
    @DisplayName("findById queries kukuvaia.sessions with parameterized id")
    @SuppressWarnings("unchecked")
    void findById_existingSession_queriesCorrectTable() {
        when(jdbcTemplate.query(contains("kukuvaia.sessions"), any(RowMapper.class), eq("s1")))
                .thenReturn(List.of());

        Optional<KukuvaiaSession> result = sessionRepository.findById("s1");

        assertThat(result).isEmpty();
        verify(jdbcTemplate).query(contains("kukuvaia.sessions"), any(RowMapper.class), eq("s1"));
    }

    @Test
    @DisplayName("findByUserId queries sessions ordered by updated_at DESC")
    @SuppressWarnings("unchecked")
    void findByUserId_validUser_queriesOrderedByUpdatedAtDesc() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("u1")))
                .thenReturn(List.of());

        List<KukuvaiaSession> result = sessionRepository.findByUserId("u1");

        assertThat(result).isEmpty();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("u1"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.sessions");
        assertThat(sqlCaptor.getValue()).contains("ORDER BY updated_at DESC");
    }

    @Test
    @DisplayName("updateName executes parameterized update on kukuvaia.sessions")
    void updateName_validInput_executesUpdate() {
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(1);

        sessionRepository.updateName("s1", "New Name");

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("New Name"), eq("s1"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.sessions");
    }

    @Test
    @DisplayName("archive sets session status to archived")
    void archive_validSession_setsStatusArchived() {
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);

        boolean result = sessionRepository.archive("s1");

        assertThat(result).isTrue();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("s1"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.sessions");
        assertThat(sqlCaptor.getValue()).containsIgnoringCase("archived");
    }
}

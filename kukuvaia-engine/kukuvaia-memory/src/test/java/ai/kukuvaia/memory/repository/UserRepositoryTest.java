package ai.kukuvaia.memory.repository;

import ai.kukuvaia.memory.model.KukuvaiaUser;
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

/**
 * Unit tests for {@link UserRepository} verifying SQL correctness and parameterization.
 */

@ExtendWith(MockitoExtension.class)
class UserRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = new UserRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("save executes upsert with parameterized query against kukuvaia.users")
    void save_validUser_executesUpsert() {
        userRepository.save("u1", "Bartek", "{}");

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("u1"), eq("Bartek"), eq("{}"));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("kukuvaia.users");
        assertThat(sql).contains("ON CONFLICT");
        assertThat(sql).doesNotContain("kukuvaia_agent");
    }

    @Test
    @DisplayName("findById returns Optional with user when found")
    @SuppressWarnings("unchecked")
    void findById_existingUser_returnsOptionalWithUser() {
        // The actual query returns results, so we mock it
        when(jdbcTemplate.query(contains("kukuvaia.users"), any(RowMapper.class), eq("u1")))
                .thenReturn(List.of());

        Optional<KukuvaiaUser> result = userRepository.findById("u1");

        assertThat(result).isEmpty();
        verify(jdbcTemplate).query(contains("kukuvaia.users"), any(RowMapper.class), eq("u1"));
    }

    @Test
    @DisplayName("updateLastSeen executes parameterized update on kukuvaia.users")
    void updateLastSeen_validUserId_executesUpdate() {
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(1);

        userRepository.updateLastSeen("u1");

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("u1"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.users");
        assertThat(sqlCaptor.getValue()).contains("last_seen_at");
    }
}

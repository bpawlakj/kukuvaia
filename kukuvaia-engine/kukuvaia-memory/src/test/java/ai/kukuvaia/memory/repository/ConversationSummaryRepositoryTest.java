package ai.kukuvaia.memory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationSummaryRepository — read/write kukuvaia.conversations.summary")
class ConversationSummaryRepositoryTest {

    @Mock private JdbcTemplate jdbcTemplate;
    private ConversationSummaryRepository repo;

    @BeforeEach
    void setUp() {
        repo = new ConversationSummaryRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("findBySessionId returns empty when no row exists")
    void findBySessionId_noRow_returnsEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("s1")))
                .thenReturn(List.of());

        Optional<String> result = repo.findBySessionId("s1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findBySessionId returns empty when row exists but summary is NULL")
    void findBySessionId_nullColumn_returnsEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("s1")))
                .thenReturn(Arrays.asList((String) null));

        Optional<String> result = repo.findBySessionId("s1");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findBySessionId returns content when populated")
    void findBySessionId_populated_returnsValue() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("s1")))
                .thenReturn(List.of("user is editing rule X with outline Y"));

        Optional<String> result = repo.findBySessionId("s1");
        assertThat(result).contains("user is editing rule X with outline Y");
    }

    @Test
    @DisplayName("findBySessionId rejects blank session id")
    void findBySessionId_blankSessionId_returnsEmpty() {
        assertThat(repo.findBySessionId("")).isEmpty();
        assertThat(repo.findBySessionId(null)).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("upsertSummary uses ON CONFLICT to insert or update")
    void upsertSummary_writes_onConflict() {
        repo.upsertSummary("s1", "summary text");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("s1"), eq("summary text"));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("kukuvaia.conversations");
        assertThat(sql).contains("ON CONFLICT (session_id)");
        assertThat(sql).contains("DO UPDATE SET summary");
    }

    @Test
    @DisplayName("upsertSummary skips when session id is blank")
    void upsertSummary_blankSessionId_skips() {
        repo.upsertSummary("", "text");
        repo.upsertSummary(null, "text");
        verifyNoInteractions(jdbcTemplate);
    }
}

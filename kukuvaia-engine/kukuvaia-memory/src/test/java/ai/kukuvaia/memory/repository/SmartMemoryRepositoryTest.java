package ai.kukuvaia.memory.repository;

import ai.kukuvaia.memory.model.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartMemoryRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SmartMemoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SmartMemoryRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("save executes upsert against kukuvaia.memories with new fields")
    @SuppressWarnings("unchecked")
    void save_validEntry_executesUpsertWithNewFields() {
        var now = Instant.now();
        var entry = new MemoryEntry(UUID.randomUUID(), "u1", "project", "key", "desc", "content",
                now, now, "explicit", 0.9, 0, null, "s1", null);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(List.of(entry));

        repository.save("u1", "project", "key", "desc", "content", "explicit", 0.9, "s1");

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(),
                any(UUID.class), eq("u1"), eq("project"), eq("key"), eq("desc"), eq("content"),
                eq("explicit"), eq(0.9), eq("s1"));

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("kukuvaia.memories");
        assertThat(sql).contains("ON CONFLICT");
        assertThat(sql).contains("memory_type");
        assertThat(sql).contains("relevance_score");
        assertThat(sql).contains("session_id");
        assertThat(sql).doesNotContain("kukuvaia_agent");
    }

    @Test
    @DisplayName("findByUser queries kukuvaia.memories with parameterized userId")
    @SuppressWarnings("unchecked")
    void findByUser_validUserId_queriesCorrectTable() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("u1")))
                .thenReturn(List.of());

        List<MemoryEntry> result = repository.findByUser("u1");

        assertThat(result).isEmpty();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("u1"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.memories");
        assertThat(sqlCaptor.getValue()).doesNotContain("kukuvaia_agent");
    }

    @Test
    @DisplayName("findByUserAndCategory queries with both parameterized filters")
    @SuppressWarnings("unchecked")
    void findByUserAndCategory_validInput_queriesWithBothFilters() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("u1"), eq("user")))
                .thenReturn(List.of());

        List<MemoryEntry> result = repository.findByUserAndCategory("u1", "user");

        assertThat(result).isEmpty();
        verify(jdbcTemplate).query(contains("kukuvaia.memories"), any(RowMapper.class), eq("u1"), eq("user"));
    }

    @Test
    @DisplayName("findByUserAndType queries memories filtered by memoryType")
    @SuppressWarnings("unchecked")
    void findByUserAndType_validInput_queriesWithTypeFilter() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("u1"), eq("explicit")))
                .thenReturn(List.of());

        List<MemoryEntry> result = repository.findByUserAndType("u1", "explicit");

        assertThat(result).isEmpty();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("u1"), eq("explicit"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.memories");
        assertThat(sqlCaptor.getValue()).contains("memory_type");
    }

    @Test
    @DisplayName("search uses full-text search with parameterized query")
    @SuppressWarnings("unchecked")
    void search_validQuery_usesFullTextSearch() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("u1"), eq("spring ai")))
                .thenReturn(List.of());

        List<MemoryEntry> result = repository.search("u1", "spring ai");

        assertThat(result).isEmpty();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("u1"), eq("spring ai"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.memories");
        assertThat(sqlCaptor.getValue()).contains("to_tsvector");
        assertThat(sqlCaptor.getValue()).contains("plainto_tsquery");
    }

    @Test
    @DisplayName("delete removes memory by userId and name from kukuvaia.memories")
    void delete_existingMemory_deletesFromCorrectTable() {
        when(jdbcTemplate.update(anyString(), eq("u1"), eq("key-name"))).thenReturn(1);

        boolean result = repository.delete("u1", "key-name");

        assertThat(result).isTrue();

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("u1"), eq("key-name"));

        assertThat(sqlCaptor.getValue()).contains("kukuvaia.memories");
        assertThat(sqlCaptor.getValue()).doesNotContain("kukuvaia_agent");
    }
}

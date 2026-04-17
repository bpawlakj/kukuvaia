package ai.kukuvaia.tools;

import ai.kukuvaia.memory.model.MemoryEntry;
import ai.kukuvaia.memory.repository.SmartMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MemoryTools — cross-session knowledge persistence")
class MemoryToolsTest {

    private MemoryTools memoryTools;
    private SmartMemoryRepository memoryRepository;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(SmartMemoryRepository.class);
        memoryTools = new MemoryTools(memoryRepository, mock(ai.kukuvaia.memory.embedding.EmbeddingService.class));
    }

    @Test
    @DisplayName("saveMemory — rejects invalid category")
    void saveMemory_invalidCategory_returnsError() {
        var result = memoryTools.saveMemory("user1", "invalid", "test", "desc", "content");
        assertThat(result).containsKey("error");
    }

    @Test
    @DisplayName("saveMemory — accepts valid category")
    void saveMemory_validCategory_saves() {
        var entry = new MemoryEntry(UUID.randomUUID(), "user1", "feedback", "test", "desc", "content",
                Instant.now(), Instant.now(), "semantic", 1.0, 0, null, null, null);
        when(memoryRepository.save(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyDouble(), any()))
                .thenReturn(entry);

        var result = memoryTools.saveMemory("user1", "feedback", "test", "desc", "content");
        assertThat(result).containsEntry("saved", true).containsEntry("category", "feedback");
    }

    @Test
    @DisplayName("listMemories — returns formatted entries")
    void listMemories_withEntries_returnsFormatted() {
        var entry = new MemoryEntry(UUID.randomUUID(), "user1", "user", "profile", "User profile", "Senior dev",
                Instant.now(), Instant.now(), "semantic", 1.0, 0, null, null, null);
        when(memoryRepository.findByUser("user1")).thenReturn(List.of(entry));

        var result = memoryTools.listMemories("user1", null);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsEntry("name", "profile");
    }
}

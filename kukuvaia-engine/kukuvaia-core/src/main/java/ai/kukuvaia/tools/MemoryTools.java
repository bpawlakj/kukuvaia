package ai.kukuvaia.tools;

import ai.kukuvaia.memory.embedding.EmbeddingService;
import ai.kukuvaia.memory.model.MemoryEntry;
import ai.kukuvaia.memory.repository.SmartMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit memory tools for agent-initiated cross-session knowledge persistence.
 * Memory is isolated per user_id — no cross-user access.
 * Embeddings are generated automatically on save when EmbeddingModel is configured.
 */
@Component
public class MemoryTools {

    private static final Logger log = LoggerFactory.getLogger(MemoryTools.class);
    private static final Set<String> VALID_CATEGORIES = Set.of("user", "project", "feedback", "reference");

    private final SmartMemoryRepository memoryRepository;
    private final EmbeddingService embeddingService;

    public MemoryTools(SmartMemoryRepository memoryRepository, EmbeddingService embeddingService) {
        this.memoryRepository = memoryRepository;
        this.embeddingService = embeddingService;
    }

    @Tool(description = "Save a memory entry for cross-session knowledge. Categories: user, project, feedback, reference.")
    public Map<String, Object> saveMemory(
            @ToolParam(description = "User ID") String userId,
            @ToolParam(description = "Category: user, project, feedback, or reference") String category,
            @ToolParam(description = "Unique name for this memory") String name,
            @ToolParam(description = "Brief description for relevance matching") String description,
            @ToolParam(description = "Full memory content") String content) {
        if (!VALID_CATEGORIES.contains(category)) {
            return Map.of("error", "Invalid category. Must be one of: " + VALID_CATEGORIES);
        }
        MemoryEntry entry = memoryRepository.save(userId, category, name, description, content,
                "semantic", 1.0, null);
        embeddingService.embedAndStore(entry.id(), description, content);
        return Map.of("saved", true, "name", entry.name(), "category", entry.category());
    }

    @Tool(description = "Search memories by text query using full-text search")
    public List<Map<String, String>> searchMemories(
            @ToolParam(description = "User ID") String userId,
            @ToolParam(description = "Search query") String query) {
        return memoryRepository.search(userId, query).stream()
                .map(e -> Map.of("name", e.name(), "category", e.category(),
                        "description", e.description(), "content", e.content()))
                .toList();
    }

    @Tool(description = "List all memories for a user, optionally filtered by category")
    public List<Map<String, String>> listMemories(
            @ToolParam(description = "User ID") String userId,
            @ToolParam(description = "Filter by category", required = false) String category) {
        var entries = category != null && !category.isBlank()
                ? memoryRepository.findByUserAndCategory(userId, category)
                : memoryRepository.findByUser(userId);
        return entries.stream()
                .map(e -> Map.of("name", e.name(), "category", e.category(), "description", e.description()))
                .toList();
    }

    @Tool(description = "Delete a memory entry by name")
    public Map<String, Object> deleteMemory(
            @ToolParam(description = "User ID") String userId,
            @ToolParam(description = "Memory name to delete") String name) {
        boolean deleted = memoryRepository.delete(userId, name);
        return Map.of("deleted", deleted, "name", name);
    }
}

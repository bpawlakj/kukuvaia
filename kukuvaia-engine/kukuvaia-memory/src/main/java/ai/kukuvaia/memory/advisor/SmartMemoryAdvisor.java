package ai.kukuvaia.memory.advisor;

import ai.kukuvaia.memory.embedding.EmbeddingService;
import ai.kukuvaia.memory.model.MemoryEntry;
import ai.kukuvaia.memory.repository.SmartMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Stream;

/**
 * Injects relevant memories into the system prompt using semantic vector search (top-K).
 * Falls back to loading all user+feedback memories when embeddings are unavailable.
 *
 * Feedback memories are always included (typically few, always relevant).
 * Access tracking bumps relevance_score to 1.0 for retrieved memories.
 */
@Component
public class SmartMemoryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SmartMemoryAdvisor.class);
    private static final String CTX_USER_ID = "kukuvaia.userId";
    private static final int TOP_K = 10;

    private final SmartMemoryRepository repository;
    private final EmbeddingService embeddingService;

    public SmartMemoryAdvisor(SmartMemoryRepository repository, EmbeddingService embeddingService) {
        this.repository = repository;
        this.embeddingService = embeddingService;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userId = (String) request.context().getOrDefault(CTX_USER_ID, "default");
        String userMessage = extractUserMessage(request);

        // Retrieve relevant memories
        List<MemoryEntry> relevant;
        if (embeddingService.isAvailable() && userMessage != null && !userMessage.isBlank()) {
            relevant = embeddingService.findSimilar(userId, userMessage, TOP_K);
            log.debug("Semantic retrieval: {} memories for user {} (query: {}...)",
                    relevant.size(), userId, userMessage.substring(0, Math.min(50, userMessage.length())));
        } else {
            // Fallback: load all user memories (existing behavior)
            relevant = repository.findByUserAndCategory(userId, "user");
            log.debug("Fallback retrieval: {} user memories for {}", relevant.size(), userId);
        }

        // Always include feedback memories (few, always relevant)
        var feedback = repository.findByUserAndCategory(userId, "feedback");

        // Merge and deduplicate by id
        var seen = new HashSet<UUID>();
        var merged = new ArrayList<MemoryEntry>();
        Stream.concat(relevant.stream(), feedback.stream()).forEach(m -> {
            if (seen.add(m.id())) {
                merged.add(m);
            }
        });

        if (merged.isEmpty()) {
            return request;
        }

        // Bump access tracking for injected memories
        merged.forEach(m -> {
            try {
                repository.touchAccess(m.id());
            } catch (Exception e) {
                log.trace("Could not touch access for memory {}", m.id());
            }
        });

        // Build memory block for system prompt
        var memoryBlock = new StringBuilder("\n\n--- PERSISTENT MEMORY (from previous sessions) ---\n");

        var userMemories = merged.stream().filter(m -> "user".equals(m.category())).toList();
        var projectMemories = merged.stream().filter(m -> "project".equals(m.category())).toList();
        var feedbackMemories = merged.stream().filter(m -> "feedback".equals(m.category())).toList();
        var referenceMemories = merged.stream().filter(m -> "reference".equals(m.category())).toList();

        appendSection(memoryBlock, "User Profile", userMemories);
        appendSection(memoryBlock, "Project Context", projectMemories);
        appendSection(memoryBlock, "Feedback & Preferences", feedbackMemories);
        appendSection(memoryBlock, "References", referenceMemories);

        log.info("Memory: injected {} for user {} ({}u/{}p/{}f/{}r)",
                merged.size(), userId, userMemories.size(), projectMemories.size(),
                feedbackMemories.size(), referenceMemories.size());

        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(memoryBlock.toString()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private static String extractUserMessage(ChatClientRequest request) {
        var userMsg = request.prompt().getUserMessage();
        return userMsg != null ? userMsg.getText() : null;
    }

    private static void appendSection(StringBuilder sb, String title, List<MemoryEntry> memories) {
        if (memories.isEmpty()) return;
        sb.append("\n### ").append(title).append("\n");
        memories.forEach(m ->
                sb.append("- ").append(m.name()).append(": ").append(m.content()).append("\n"));
    }
}

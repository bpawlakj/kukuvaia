package ai.kukuvaia.memory.embedding;

import ai.kukuvaia.memory.model.MemoryEntry;
import ai.kukuvaia.memory.repository.SmartMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Wraps Spring AI EmbeddingModel for memory vector operations.
 * Generates embeddings for memories and provides semantic similarity search via pgvector.
 * Gracefully degrades when no EmbeddingModel is configured.
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final SmartMemoryRepository repository;
    private final EmbeddingModel embeddingModel;

    public EmbeddingService(SmartMemoryRepository repository,
                            @Nullable EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        if (embeddingModel != null) {
            log.info("EmbeddingService initialized with model: {}", embeddingModel.getClass().getSimpleName());
        } else {
            log.warn("EmbeddingService: no EmbeddingModel configured — vector search disabled, using keyword fallback");
        }
    }

    /**
     * Whether vector operations are available.
     */
    public boolean isAvailable() {
        return embeddingModel != null;
    }

    /**
     * Generate embedding for text and return the float array.
     */
    public float[] embed(String text) {
        if (embeddingModel == null) {
            return null;
        }
        return embeddingModel.embed(text);
    }

    /**
     * Generate embedding for a memory and store it in the database.
     */
    public void embedAndStore(UUID memoryId, String description, String content) {
        if (embeddingModel == null) return;
        try {
            String text = description + " " + content;
            float[] vector = embeddingModel.embed(text);
            repository.updateEmbedding(memoryId, vector);
            log.debug("Embedded memory {}", memoryId);
        } catch (Exception e) {
            log.warn("Failed to embed memory {}: {}", memoryId, e.getMessage());
        }
    }

    /**
     * Find top-K most similar memories for a user by vector similarity.
     * Falls back to keyword search if embeddings are not available.
     */
    public List<MemoryEntry> findSimilar(String userId, String query, int topK) {
        if (embeddingModel == null) {
            return repository.search(userId, query);
        }
        try {
            float[] queryVector = embeddingModel.embed(query);
            return repository.findBySimilarity(userId, queryVector, topK);
        } catch (Exception e) {
            log.warn("Vector search failed, falling back to keyword: {}", e.getMessage());
            return repository.search(userId, query);
        }
    }
}

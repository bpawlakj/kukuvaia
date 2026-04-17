package ai.kukuvaia.memory.consolidation;

import ai.kukuvaia.memory.repository.SmartMemoryRepository;
import ai.kukuvaia.memory.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Scheduled memory consolidation job.
 * Runs daily (configurable) to maintain memory quality:
 * 1. Decay — reduce relevance of unused memories
 * 2. Merge — deduplicate near-identical memories by vector similarity
 * 3. Prune — delete expired memories
 *
 * Can also be invoked manually via {@link #consolidateAll()}.
 */
@Component
public class MemoryConsolidationJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationJob.class);

    private final SmartMemoryRepository memoryRepository;
    private final UserRepository userRepository;

    @Value("${kukuvaia.memory.consolidation.decay-factor:0.95}")
    private double decayFactor;

    @Value("${kukuvaia.memory.consolidation.unaccessed-days:7}")
    private int unaccessedDays;

    @Value("${kukuvaia.memory.consolidation.similarity-threshold:0.95}")
    private double similarityThreshold;

    public MemoryConsolidationJob(SmartMemoryRepository memoryRepository,
                                   UserRepository userRepository) {
        this.memoryRepository = memoryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Scheduled consolidation — default daily at 3am.
     */
    @Scheduled(cron = "${kukuvaia.memory.consolidation.cron:0 0 3 * * *}")
    public void scheduledConsolidation() {
        log.info("Memory consolidation started (scheduled)");
        consolidateAll();
    }

    /**
     * Run full consolidation cycle for all users.
     */
    public void consolidateAll() {
        long start = System.currentTimeMillis();

        int decayed = decay();
        int merged = mergeAllUsers();
        int pruned = prune();

        long duration = System.currentTimeMillis() - start;
        log.info("Memory consolidation complete in {}ms: {} decayed, {} merged, {} pruned",
                duration, decayed, merged, pruned);
    }

    /**
     * Step 1: Decay relevance for memories not accessed recently.
     * Memories unused for N days get relevance_score *= decay_factor.
     * Floor at 0.1 — never auto-delete, just deprioritize in retrieval.
     */
    public int decay() {
        int decayed = memoryRepository.decayRelevance(decayFactor, unaccessedDays);
        if (decayed > 0) {
            log.info("Decay: {} memories reduced (factor={}, unaccessed >{}d)",
                    decayed, decayFactor, unaccessedDays);
        }
        return decayed;
    }

    /**
     * Step 2: Merge near-duplicate memories across all users.
     * Finds pairs with cosine similarity > threshold in the same category.
     * Keeps the one with higher relevance_score, deletes the other.
     */
    public int mergeAllUsers() {
        var users = userRepository.findAll();
        int totalMerged = 0;

        for (var user : users) {
            totalMerged += mergeForUser(user.id());
        }

        return totalMerged;
    }

    private int mergeForUser(String userId) {
        var duplicates = memoryRepository.findNearDuplicates(userId, similarityThreshold, 20);
        int merged = 0;

        for (Map<String, Object> pair : duplicates) {
            UUID idA = (UUID) pair.get("id_a");
            UUID idB = (UUID) pair.get("id_b");
            String nameA = (String) pair.get("name_a");
            String nameB = (String) pair.get("name_b");
            double scoreA = ((Number) pair.get("score_a")).doubleValue();
            double scoreB = ((Number) pair.get("score_b")).doubleValue();
            double similarity = ((Number) pair.get("similarity")).doubleValue();

            // Keep the one with higher relevance, delete the other
            if (scoreA >= scoreB) {
                memoryRepository.deleteById(idB);
                log.info("Merged: deleted '{}' (sim={} with '{}')", nameB, similarity, nameA);
            } else {
                memoryRepository.deleteById(idA);
                log.info("Merged: deleted '{}' (sim={} with '{}')", nameA, similarity, nameB);
            }
            merged++;
        }

        return merged;
    }

    /**
     * Step 3: Delete memories past their expiration date.
     */
    public int prune() {
        int pruned = memoryRepository.pruneExpired();
        if (pruned > 0) {
            log.info("Pruned: {} expired memories deleted", pruned);
        }
        return pruned;
    }
}

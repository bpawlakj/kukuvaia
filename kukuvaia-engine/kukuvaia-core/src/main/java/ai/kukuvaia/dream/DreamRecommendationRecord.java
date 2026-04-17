package ai.kukuvaia.dream;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DreamRecommendationRecord(
        UUID id,
        UUID reportId,
        String type,            // MODEL_UPGRADE, MEMORY_CLEANUP, CONFIG_INCONSISTENCY, etc.
        String priority,        // critical, high, medium, low, informational
        String description,
        String suggestedAction,
        double confidence,
        Map<String, Object> evidence,
        String status,          // pending, accepted, rejected, expired
        Instant resolvedAt,
        String resolvedBy,
        String rejectionReason,
        Instant createdAt
) {}

package ai.kukuvaia.dream;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DreamReportRecord(
        UUID id,
        Instant startedAt,
        Instant completedAt,
        String status,          // running, completed, failed, cancelled
        int tokenCost,
        List<String> modelsUsed,
        Map<String, Object> healthSnapshot,
        String summary,
        Instant createdAt
) {}

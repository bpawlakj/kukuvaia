package ai.kukuvaia.api;

import ai.kukuvaia.dream.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dream report and recommendation management endpoints.
 */
@RestController
@RequestMapping("/api/dream")
public class DreamController {

    private final DreamService dreamService;

    public DreamController(DreamService dreamService) {
        this.dreamService = dreamService;
    }

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerDream() {
        UUID reportId = dreamService.runDream();
        return ResponseEntity.ok(Map.of("reportId", reportId, "status", "triggered"));
    }

    @GetMapping("/reports")
    public ResponseEntity<List<DreamReportRecord>> listReports(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(dreamService.listReports(limit));
    }

    @GetMapping("/reports/latest")
    public ResponseEntity<DreamReportRecord> latestReport() {
        return dreamService.getLatestReport()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<DreamReportRecord> getReport(@PathVariable UUID id) {
        return dreamService.getReport(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reports/{id}/recommendations")
    public ResponseEntity<List<DreamRecommendationRecord>> getRecommendations(@PathVariable UUID id) {
        return ResponseEntity.ok(dreamService.getRecommendations(id));
    }

    @GetMapping("/recommendations/pending")
    public ResponseEntity<List<DreamRecommendationRecord>> pendingRecommendations() {
        return ResponseEntity.ok(dreamService.getPendingRecommendations());
    }

    @PostMapping("/recommendations/{id}/accept")
    public ResponseEntity<Void> accept(@PathVariable UUID id) {
        return dreamService.acceptRecommendation(id, "api")
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/recommendations/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id,
                                       @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "");
        return dreamService.rejectRecommendation(id, "api", reason)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }
}

package ai.kukuvaia.api;

import ai.kukuvaia.memory.consolidation.MemoryConsolidationJob;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin endpoints for manual operations.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final MemoryConsolidationJob consolidationJob;

    public AdminController(MemoryConsolidationJob consolidationJob) {
        this.consolidationJob = consolidationJob;
    }

    @PostMapping("/consolidate")
    public ResponseEntity<Map<String, String>> consolidate() {
        consolidationJob.consolidateAll();
        return ResponseEntity.ok(Map.of("status", "completed"));
    }
}

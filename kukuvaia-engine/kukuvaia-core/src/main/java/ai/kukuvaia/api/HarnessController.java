package ai.kukuvaia.api;

import ai.kukuvaia.harness.HarnessRule;
import ai.kukuvaia.harness.HarnessService;
import ai.kukuvaia.harness.MutableHarnessRuleStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin panel for harness rules. Operates entirely against the {@link MutableHarnessRuleStore}
 * (filesystem by default — see {@code FilesystemHarnessRuleStore}); no DB tables are touched.
 *
 * <p>The store is the single source of truth: an external editor change shows up here on the next
 * read, and admin edits made through this controller are visible to the resolver immediately
 * (cache invalidation on every write).
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET    /api/harness/rules}         — list parsed rules (all scopes)</li>
 *   <li>{@code GET    /api/harness/rules/{name}}  — fetch one rule (returns parsed metadata)</li>
 *   <li>{@code GET    /api/harness/rules/{name}/raw}  — raw markdown for editor display</li>
 *   <li>{@code PUT    /api/harness/rules/{name}}  — save raw markdown (create or replace)</li>
 *   <li>{@code DELETE /api/harness/rules/{name}}  — delete a rule</li>
 *   <li>{@code POST   /api/harness/reload}        — invalidate the resolver cache (manual nudge)</li>
 *   <li>{@code GET    /api/harness/resolve}       — preview compiled rules for a userId</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final HarnessService harnessService;
    private final MutableHarnessRuleStore store;

    public HarnessController(HarnessService harnessService, MutableHarnessRuleStore store) {
        this.harnessService = harnessService;
        this.store = store;
    }

    @GetMapping("/rules")
    public ResponseEntity<List<HarnessRule>> list() {
        return ResponseEntity.ok(harnessService.listAll());
    }

    @GetMapping("/rules/{name}")
    public ResponseEntity<HarnessRule> getOne(@PathVariable String name) {
        return harnessService.listAll().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns the raw markdown (frontmatter + body) for the named rule. {@code text/markdown} so
     * the admin UI can render directly without re-serializing.
     */
    @GetMapping(value = "/rules/{name}/raw", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> getRaw(@PathVariable String name) {
        return harnessService.readRaw(name)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Create or replace a rule. Body is the raw markdown ({@code text/markdown} or
     * {@code text/plain}). Cache is invalidated on success so the next resolve sees the new rule.
     */
    @PutMapping(value = "/rules/{name}",
            consumes = {"text/markdown", MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HarnessRule> upsert(@PathVariable String name, @RequestBody String body) {
        HarnessRule saved = store.save(name, body);
        harnessService.invalidateAllCaches();
        return ResponseEntity.status(HttpStatus.OK).body(saved);
    }

    @DeleteMapping("/rules/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        boolean removed = store.delete(name);
        if (!removed) return ResponseEntity.notFound().build();
        harnessService.invalidateAllCaches();
        return ResponseEntity.noContent().build();
    }

    /**
     * Manual cache nudge — called by tooling that edits rules outside the API (git pull,
     * S3 sync, hand edits) and wants the resolver to pick them up without waiting for the
     * 5-minute TTL.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        harnessService.invalidateAllCaches();
        return ResponseEntity.ok(Map.of(
                "reloaded", true,
                "ruleCount", harnessService.listAll().size()));
    }

    /** Preview the compiled output the resolver would inject for a given user (or anonymous). */
    @GetMapping("/resolve")
    public ResponseEntity<Map<String, String>> resolve(@RequestParam(required = false) String userId) {
        String compiled = harnessService.resolveForUser(userId);
        return ResponseEntity.ok(Map.of("rules", compiled == null ? "" : compiled));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

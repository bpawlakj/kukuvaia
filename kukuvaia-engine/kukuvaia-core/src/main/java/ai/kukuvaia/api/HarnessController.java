package ai.kukuvaia.api;

import ai.kukuvaia.harness.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Harness engineering endpoints: groups, rule sets, rules, resolution preview.
 */
@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private final HarnessService harnessService;

    public HarnessController(HarnessService harnessService) {
        this.harnessService = harnessService;
    }

    // --- Groups ---

    @PostMapping("/groups")
    public ResponseEntity<GroupRecord> createGroup(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(harnessService.createGroup(name, body.get("description"), null));
    }

    @GetMapping("/groups")
    public ResponseEntity<List<GroupRecord>> listGroups() {
        return ResponseEntity.ok(harnessService.listGroups());
    }

    @GetMapping("/groups/{id}")
    public ResponseEntity<GroupRecord> getGroup(@PathVariable UUID id) {
        return harnessService.getGroup(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        return harnessService.deleteGroup(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/groups/{id}/members")
    public ResponseEntity<Void> addMember(@PathVariable UUID id,
                                          @RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        if (userId == null) return ResponseEntity.badRequest().build();
        harnessService.addGroupMember(id, userId, body.getOrDefault("role", "member"));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/groups/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID groupId, @PathVariable String userId) {
        harnessService.removeGroupMember(groupId, userId);
        return ResponseEntity.noContent().build();
    }

    // --- Rule Sets ---

    @PostMapping("/rule-sets")
    public ResponseEntity<RuleSetRecord> createRuleSet(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) return ResponseEntity.badRequest().build();
        String ownerId = (String) body.get("ownerId");
        return ResponseEntity.status(HttpStatus.CREATED).body(
                harnessService.createRuleSet(
                        name,
                        (String) body.get("description"),
                        (String) body.getOrDefault("scope", "platform"),
                        (String) body.get("ownerType"),
                        ownerId,
                        body.containsKey("priority") ? ((Number) body.get("priority")).intValue() : 0
                ));
    }

    @GetMapping("/rule-sets")
    public ResponseEntity<List<RuleSetRecord>> listRuleSets() {
        return ResponseEntity.ok(harnessService.listRuleSets());
    }

    @GetMapping("/rule-sets/{id}")
    public ResponseEntity<RuleSetRecord> getRuleSet(@PathVariable UUID id) {
        return harnessService.getRuleSet(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/rule-sets/{id}")
    public ResponseEntity<Void> deleteRuleSet(@PathVariable UUID id) {
        return harnessService.deleteRuleSet(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // --- Rules ---

    @PostMapping("/rule-sets/{ruleSetId}/rules")
    @SuppressWarnings("unchecked")
    public ResponseEntity<RuleRecord> createRule(@PathVariable UUID ruleSetId,
                                                 @RequestBody Map<String, Object> body) {
        String key = (String) body.get("key");
        String type = (String) body.get("type");
        String content = (String) body.get("content");
        if (key == null || type == null || content == null) return ResponseEntity.badRequest().build();

        return ResponseEntity.status(HttpStatus.CREATED).body(
                harnessService.createRule(ruleSetId, key, type, content,
                        (List<String>) body.get("tags"),
                        (Map<String, Object>) body.get("activation"),
                        body.containsKey("priority") ? ((Number) body.get("priority")).intValue() : 0
                ));
    }

    @GetMapping("/rule-sets/{ruleSetId}/rules")
    public ResponseEntity<List<RuleRecord>> listRules(@PathVariable UUID ruleSetId) {
        return ResponseEntity.ok(harnessService.listRules(ruleSetId));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        return harnessService.deleteRule(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // --- Resolution preview ---

    @GetMapping("/resolve")
    public ResponseEntity<Map<String, String>> resolve(
            @RequestParam(required = false) String userId) {
        String compiled = harnessService.resolveForUser(userId);
        return ResponseEntity.ok(Map.of("rules", compiled != null ? compiled : ""));
    }
}

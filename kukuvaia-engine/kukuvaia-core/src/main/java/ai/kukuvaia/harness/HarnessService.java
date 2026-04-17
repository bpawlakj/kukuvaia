package ai.kukuvaia.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves applicable rules for a user by collecting from all hierarchy levels:
 * platform → group → user → project → session.
 * Lower scope overrides higher scope for the same key.
 * Results are cached per-user with TTL.
 */
@Service
public class HarnessService {

    private static final Logger log = LoggerFactory.getLogger(HarnessService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private final RuleSetRepository ruleSetRepository;
    private final RuleRepository ruleRepository;
    private final GroupRepository groupRepository;

    private final ConcurrentHashMap<String, CachedHarness> cache = new ConcurrentHashMap<>();

    public HarnessService(RuleSetRepository ruleSetRepository,
                          RuleRepository ruleRepository,
                          GroupRepository groupRepository) {
        this.ruleSetRepository = ruleSetRepository;
        this.ruleRepository = ruleRepository;
        this.groupRepository = groupRepository;
    }

    /**
     * Resolve all active rules for a user, compiled into a system prompt section.
     * Uses cache with 5-minute TTL.
     */
    public String resolveForUser(String userId) {
        var cached = cache.get(userId);
        if (cached != null && !cached.isExpired()) {
            return cached.compiledRules;
        }

        String compiled = compileRules(userId);
        cache.put(userId, new CachedHarness(compiled, System.currentTimeMillis()));
        return compiled;
    }

    /**
     * Invalidate cache for a user (called after rule changes).
     */
    public void invalidateCache(String userId) {
        cache.remove(userId);
    }

    /**
     * Invalidate all caches (called after global rule changes).
     */
    public void invalidateAllCaches() {
        cache.clear();
    }

    /**
     * Collect and compile rules from all hierarchy levels.
     */
    String compileRules(String userId) {
        // Collect rules by key (lower scope overrides higher)
        Map<String, RuleRecord> rulesByKey = new LinkedHashMap<>();

        // 1. Platform rules (scope=platform)
        collectRules(ruleSetRepository.findByScope("platform"), rulesByKey);

        // 2. Group rules (for all groups the user belongs to)
        if (userId != null) {
            List<UUID> groupIds = groupRepository.findGroupIdsByUserId(userId);
            for (UUID groupId : groupIds) {
                collectRules(ruleSetRepository.findByOwner("group", groupId), rulesByKey);
            }

            // 3. User rules (scope=user, owned by this user)
            collectRules(ruleSetRepository.findByOwner("user", userId), rulesByKey);
        }

        if (rulesByKey.isEmpty()) {
            return "";
        }

        // Compile into markdown sections by type
        var sb = new StringBuilder();
        sb.append("## Active Rules\n\n");

        var byType = new LinkedHashMap<String, List<RuleRecord>>();
        for (RuleRecord rule : rulesByKey.values()) {
            byType.computeIfAbsent(rule.type(), k -> new ArrayList<>()).add(rule);
        }

        for (var entry : byType.entrySet()) {
            sb.append("### ").append(formatType(entry.getKey())).append("\n");
            for (RuleRecord rule : entry.getValue()) {
                sb.append("- ").append(rule.content()).append("\n");
            }
            sb.append("\n");
        }

        log.debug("Compiled {} rules for user {}", rulesByKey.size(), userId);
        return sb.toString().trim();
    }

    private void collectRules(List<RuleSetRecord> ruleSets, Map<String, RuleRecord> target) {
        for (RuleSetRecord ruleSet : ruleSets) {
            List<RuleRecord> rules = ruleRepository.findByRuleSetId(ruleSet.id());
            for (RuleRecord rule : rules) {
                // Lower scope overrides higher scope (same key)
                target.put(rule.key(), rule);
            }
        }
    }

    private String formatType(String type) {
        return switch (type) {
            case "instruction" -> "Instructions";
            case "constraint" -> "Constraints";
            case "context" -> "Context";
            case "preference" -> "Preferences";
            case "persona_modifier" -> "Persona";
            default -> type;
        };
    }

    // --- CRUD delegation ---

    public GroupRecord createGroup(String name, String description, UUID parentId) {
        return groupRepository.save(name, description, parentId);
    }

    public List<GroupRecord> listGroups() {
        return groupRepository.findAll();
    }

    public Optional<GroupRecord> getGroup(UUID id) {
        return groupRepository.findById(id);
    }

    public boolean deleteGroup(UUID id) {
        return groupRepository.delete(id) > 0;
    }

    public void addGroupMember(UUID groupId, String userId, String role) {
        groupRepository.addMember(groupId, userId, role);
        invalidateCache(userId);
    }

    public void removeGroupMember(UUID groupId, String userId) {
        groupRepository.removeMember(groupId, userId);
        invalidateCache(userId);
    }

    public RuleSetRecord createRuleSet(String name, String description, String scope,
                                       String ownerType, String ownerId, int priority) {
        return ruleSetRepository.save(name, description, scope, ownerType, ownerId, priority);
    }

    public List<RuleSetRecord> listRuleSets() {
        return ruleSetRepository.findAll();
    }

    public Optional<RuleSetRecord> getRuleSet(UUID id) {
        return ruleSetRepository.findById(id);
    }

    public boolean deleteRuleSet(UUID id) {
        invalidateAllCaches();
        return ruleSetRepository.delete(id) > 0;
    }

    public RuleRecord createRule(UUID ruleSetId, String key, String type, String content,
                                 List<String> tags, Map<String, Object> activation, int priority) {
        invalidateAllCaches();
        return ruleRepository.save(ruleSetId, key, type, content, tags, activation, priority);
    }

    public List<RuleRecord> listRules(UUID ruleSetId) {
        return ruleRepository.findByRuleSetId(ruleSetId);
    }

    public boolean deleteRule(UUID id) {
        invalidateAllCaches();
        return ruleRepository.delete(id) > 0;
    }

    record CachedHarness(String compiledRules, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}

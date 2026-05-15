package ai.kukuvaia.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves applicable harness rules for a caller (interactive user OR non-interactive agent run)
 * by reading from a {@link HarnessRuleStore}. The compiled markdown is what {@link HarnessAdvisor}
 * injects into the system prompt.
 *
 * <p>Scope hierarchy is two levels — platform → user. Lower scope overrides higher for the same
 * {@code key}, so a user can opt out of (or replace) a platform rule. Group scope was dropped in
 * the file-backed rewrite; it can be reintroduced as a separate iteration when there's a real
 * group-membership store backing it (the legacy DB-only group concept never had real users).
 *
 * <p>For non-interactive callers (P23 agent runs) the {@code userId} is {@code null} — only
 * platform rules apply, which is exactly the right behavior for system-to-system dispatches.
 *
 * <p>Caching: the compiled output per (cache-key) is cached for 5 minutes. Writes via
 * {@link MutableHarnessRuleStore} should be paired with {@link #invalidateAllCaches()} so admin
 * panel edits land immediately on the next resolve.
 */
@Service
public class HarnessService {

    private static final Logger log = LoggerFactory.getLogger(HarnessService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    /**
     * Sentinel cache key for callers that have no user context — typically non-interactive agent
     * runs (P23) where the ChatClient never sets {@code kukuvaia.userId}. Without this, the cache
     * would fail on a {@code Map.get(null)} lookup.
     */
    private static final String NO_USER_CACHE_KEY = "__no_user__";

    private final HarnessRuleStore store;
    private final ConcurrentHashMap<String, CachedHarness> cache = new ConcurrentHashMap<>();

    public HarnessService(HarnessRuleStore store) {
        this.store = store;
    }

    /**
     * Resolve all active rules for a user, compiled into a system prompt section. A null
     * {@code userId} resolves to the platform-only rule set.
     */
    public String resolveForUser(String userId) {
        String cacheKey = userId == null ? NO_USER_CACHE_KEY : userId;
        var cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.compiledRules;
        }

        String compiled = compileRules(userId);
        cache.put(cacheKey, new CachedHarness(compiled, System.currentTimeMillis()));
        return compiled;
    }

    /**
     * Invalidate cache for a user (called after rule changes via the admin panel). A null userId
     * targets the platform-only entry written by anonymous resolutions.
     */
    public void invalidateCache(String userId) {
        cache.remove(userId == null ? NO_USER_CACHE_KEY : userId);
    }

    /** Invalidate everything — typical after an admin panel write or a bulk reload. */
    public void invalidateAllCaches() {
        cache.clear();
    }

    /**
     * Collect and compile rules for a userId. Lower scope (user) overrides higher scope (platform)
     * for the same {@link HarnessRule#key()}. Disabled rules are dropped at this layer so they stay
     * visible in the admin store but don't reach the LLM.
     */
    String compileRules(String userId) {
        List<HarnessRule> all = store.loadAll();

        // 1. Collect platform first, then overlay user rules so user wins on key collision.
        Map<String, HarnessRule> rulesByKey = new LinkedHashMap<>();
        for (HarnessRule rule : all) {
            if (!rule.enabled()) continue;
            if (rule.isPlatformScope()) rulesByKey.put(rule.key(), rule);
        }
        if (userId != null) {
            for (HarnessRule rule : all) {
                if (!rule.enabled()) continue;
                if (rule.appliesToUser(userId)) rulesByKey.put(rule.key(), rule);
            }
        }

        if (rulesByKey.isEmpty()) return "";

        // Sort rules within each type by priority desc, name asc — stable, deterministic.
        Map<String, List<HarnessRule>> byType = new LinkedHashMap<>();
        for (HarnessRule rule : rulesByKey.values()) {
            byType.computeIfAbsent(rule.type(), k -> new ArrayList<>()).add(rule);
        }
        var priorityComparator = Comparator
                .comparingInt(HarnessRule::priority).reversed()
                .thenComparing(HarnessRule::name);
        byType.values().forEach(list -> list.sort(priorityComparator));

        var sb = new StringBuilder("## Active Rules\n\n");
        for (var entry : byType.entrySet()) {
            sb.append("### ").append(formatType(entry.getKey())).append("\n");
            for (HarnessRule rule : entry.getValue()) {
                sb.append("- ").append(rule.content().strip()).append("\n");
            }
            sb.append("\n");
        }

        log.debug("Compiled {} rules for {}", rulesByKey.size(),
                userId == null ? "(no user)" : "user " + userId);
        return sb.toString().trim();
    }

    private String formatType(String type) {
        return switch (type) {
            case "instruction" -> "Instructions";
            case "constraint" -> "Constraints";
            case "context" -> "Context";
            case "preference" -> "Preferences";
            default -> type;
        };
    }

    // --- Admin-facing read helpers -------------------------------------

    public List<HarnessRule> listAll() {
        return store.loadAll();
    }

    public List<String> listNames() {
        return store.listNames();
    }

    public Optional<String> readRaw(String name) {
        return store.readRaw(name);
    }

    record CachedHarness(String compiledRules, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}

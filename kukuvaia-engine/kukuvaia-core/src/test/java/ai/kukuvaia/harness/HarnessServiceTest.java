package ai.kukuvaia.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of {@link HarnessService} with the file-backed store. Uses an in-memory fake store
 * so the test is fully hermetic — no temp directories, no Mockito ceremony for the read API.
 */
@DisplayName("HarnessService — file-backed resolution")
class HarnessServiceTest {

    private FakeStore store;
    private HarnessService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        service = new HarnessService(store);
    }

    @Test
    @DisplayName("no rules — returns empty string")
    void noRules_empty() {
        assertThat(service.resolveForUser("bartek")).isEmpty();
        assertThat(service.resolveForUser(null)).isEmpty();
    }

    @Test
    @DisplayName("platform rules apply to everyone (including null userId for agent runs)")
    void platformRules_applyToAll() {
        store.add(rule("di", "di", "platform", "instruction", "Use constructor injection only.", 0, true));

        assertThat(service.resolveForUser("alice")).contains("constructor injection");
        assertThat(service.resolveForUser(null)).contains("constructor injection");
    }

    @Test
    @DisplayName("user rules apply only to that user; not visible to anonymous resolves")
    void userRules_scoped() {
        store.add(rule("verbose", "verbose", "user:bartek", "preference", "Use verbose explanations.", 0, true));

        assertThat(service.resolveForUser("bartek")).contains("verbose explanations");
        assertThat(service.resolveForUser("alice")).isEmpty();
        assertThat(service.resolveForUser(null)).isEmpty();
    }

    @Test
    @DisplayName("user rule overrides platform rule on same key")
    void userOverridesPlatform() {
        store.add(rule("style", "style", "platform", "preference", "Be concise.", 0, true));
        store.add(rule("verbose-style", "style", "user:bartek", "preference", "Be verbose for me.", 0, true));

        // platform-only resolution sees just the platform rule
        assertThat(service.resolveForUser(null)).contains("Be concise");
        // bartek's resolution overrides via shared key
        String compiled = service.resolveForUser("bartek");
        assertThat(compiled).contains("Be verbose for me");
        assertThat(compiled).doesNotContain("Be concise");
    }

    @Test
    @DisplayName("disabled rules are dropped at compile, never reach LLM")
    void disabledRules_skipped() {
        store.add(rule("active", "a", "platform", "instruction", "Active rule body.", 0, true));
        store.add(rule("inactive", "b", "platform", "instruction", "Inactive rule body.", 0, false));

        String compiled = service.resolveForUser(null);
        assertThat(compiled).contains("Active rule body");
        assertThat(compiled).doesNotContain("Inactive rule body");
    }

    @Test
    @DisplayName("rules grouped by type with priority desc, name asc within each section")
    void groupingAndOrdering() {
        store.add(rule("a-instr", "ai", "platform", "instruction", "First instr.", 0, true));
        store.add(rule("b-instr", "bi", "platform", "instruction", "Second instr.", 10, true));
        store.add(rule("c-cons", "cc", "platform", "constraint", "A constraint.", 0, true));

        String compiled = service.resolveForUser(null);
        // Higher priority comes first within Instructions section
        int second = compiled.indexOf("Second instr");
        int first = compiled.indexOf("First instr");
        assertThat(second).isLessThan(first);

        // Section headers exist
        assertThat(compiled).contains("### Instructions");
        assertThat(compiled).contains("### Constraints");
    }

    @Test
    @DisplayName("cache: second resolve hits cache (single store.loadAll call per cacheKey)")
    void cache_secondResolveHitsCache() {
        store.add(rule("x", "x", "platform", "instruction", "body", 0, true));

        service.resolveForUser("alice");
        service.resolveForUser("alice");

        assertThat(store.loadAllCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("anonymous resolves share the sentinel cache key (P23 agent runs)")
    void anonymousResolve_sharesCacheKey() {
        store.add(rule("x", "x", "platform", "instruction", "body", 0, true));

        service.resolveForUser(null);
        service.resolveForUser(null);

        assertThat(store.loadAllCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalidateCache(null) does not NPE and forces re-resolution")
    void invalidateAnonymous_doesNotNpe() {
        store.add(rule("x", "x", "platform", "instruction", "body", 0, true));

        service.resolveForUser(null);
        service.invalidateCache(null);
        service.resolveForUser(null);

        assertThat(store.loadAllCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidateAllCaches forces every key to re-resolve")
    void invalidateAll_clearsEverything() {
        store.add(rule("x", "x", "platform", "instruction", "body", 0, true));

        service.resolveForUser("alice");
        service.resolveForUser(null);
        service.invalidateAllCaches();
        service.resolveForUser("alice");
        service.resolveForUser(null);

        assertThat(store.loadAllCalls.get()).isEqualTo(4);
    }

    // --- helpers --------------------------------------------------------

    private static HarnessRule rule(String name, String key, String scope, String type,
                                    String content, int priority, boolean enabled) {
        return new HarnessRule(name, key, scope, type, content, priority, enabled, List.of());
    }

    /** In-memory store that counts loadAll calls so we can assert cache behavior precisely. */
    static final class FakeStore implements HarnessRuleStore {
        final List<HarnessRule> rules = new ArrayList<>();
        final AtomicInteger loadAllCalls = new AtomicInteger();

        void add(HarnessRule r) { rules.add(r); }

        @Override public List<HarnessRule> loadAll() {
            loadAllCalls.incrementAndGet();
            return List.copyOf(rules);
        }

        @Override public Optional<String> readRaw(String name) { return Optional.empty(); }

        @Override public List<String> listNames() {
            return rules.stream().map(HarnessRule::name).toList();
        }
    }
}

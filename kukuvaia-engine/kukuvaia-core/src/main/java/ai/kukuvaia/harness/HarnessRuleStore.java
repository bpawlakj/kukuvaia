package ai.kukuvaia.harness;

import java.util.List;
import java.util.Optional;

/**
 * Storage port for {@link HarnessRule}s. Implementations may back this with the local filesystem
 * (default — see {@code FilesystemHarnessRuleStore}), S3, a git repository, or any other source
 * that can produce a stable list of named markdown documents.
 *
 * <p>Decoupling storage from {@link HarnessService} lets the admin panel and the rule resolver
 * share one source of truth, while keeping the option to swap in a new backend later (e.g.,
 * "harness rules live in a separate git repo we pull on a webhook") without touching the resolver.
 *
 * <p>{@link MutableHarnessRuleStore} extends this with write operations — admin endpoints depend
 * on the mutable shape, while {@link HarnessAdvisor} only needs read access. Implementations that
 * are intrinsically read-only (e.g., classpath baseline) implement only the read interface.
 */
public interface HarnessRuleStore {

    /**
     * Snapshot of every parsed rule the store currently holds. Filtering by scope (platform / user)
     * is the resolver's job, not the store's — keeping this a flat list makes alternative
     * implementations (S3 prefix scan, git tree walk) straightforward.
     */
    List<HarnessRule> loadAll();

    /**
     * Raw markdown (frontmatter + body) for one rule, addressed by its {@code name}. Used by the
     * admin panel to render edit views. Returns empty if the name is unknown.
     */
    Optional<String> readRaw(String name);

    /** Names known to the store, in stable lexical order. Used to populate the admin list view. */
    List<String> listNames();
}

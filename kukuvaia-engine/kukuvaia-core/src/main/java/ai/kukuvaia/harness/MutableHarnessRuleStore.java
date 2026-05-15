package ai.kukuvaia.harness;

/**
 * Read-write extension of {@link HarnessRuleStore} for stores backed by mutable storage
 * (filesystem, S3, git). The admin panel writes via this interface; immutable stores
 * (classpath baseline, frozen snapshots) deliberately do NOT implement it so the type system
 * prevents accidental writes to a read-only source.
 */
public interface MutableHarnessRuleStore extends HarnessRuleStore {

    /**
     * Persist (or overwrite) a rule from raw markdown (frontmatter + body). Implementations parse
     * eagerly so a malformed payload fails fast at the controller boundary, not at next read.
     *
     * @return the parsed {@link HarnessRule} as it now lives in the store
     * @throws IllegalArgumentException if the markdown cannot be parsed or the name is reserved
     */
    HarnessRule save(String name, String rawMarkdown);

    /**
     * Remove a rule by name. Returns {@code true} if a rule was actually removed, {@code false}
     * if no rule with that name existed (so callers can map cleanly to 404 vs 204).
     */
    boolean delete(String name);
}

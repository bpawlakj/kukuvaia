package ai.kukuvaia.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filesystem store behavior — load/save/list/delete + name-validation defence against path
 * traversal. Uses {@code @TempDir} so tests are hermetic; no shared fixtures.
 */
@DisplayName("FilesystemHarnessRuleStore")
class FilesystemHarnessRuleStoreTest {

    @Test
    @DisplayName("loadAll on empty directory returns empty list, never throws")
    void loadAll_emptyDir(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());
        assertThat(store.loadAll()).isEmpty();
        assertThat(store.listNames()).isEmpty();
    }

    @Test
    @DisplayName("save writes file, loadAll reads it back, listNames sees it")
    void save_thenLoad(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());

        store.save("di-policy", """
                ---
                type: instruction
                ---
                Use constructor injection only.
                """);

        List<HarnessRule> rules = store.loadAll();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).name()).isEqualTo("di-policy");
        assertThat(rules.get(0).content()).isEqualTo("Use constructor injection only.");
        assertThat(store.listNames()).containsExactly("di-policy");
    }

    @Test
    @DisplayName("loadAll skips malformed files but keeps the rest (no whole-store poisoning)")
    void loadAll_skipsMalformed(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("good.md"),
                "---\ntype: instruction\n---\ngood body\n");
        Files.writeString(tmp.resolve("bad.md"),
                "no frontmatter at all");

        var store = new FilesystemHarnessRuleStore(tmp.toString());
        List<HarnessRule> rules = store.loadAll();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).name()).isEqualTo("good");
    }

    @Test
    @DisplayName("readRaw returns the on-disk content verbatim for editor display")
    void readRaw_returnsVerbatim(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());
        String md = "---\ntype: instruction\n---\nverbatim body\n";
        store.save("rule-x", md);

        assertThat(store.readRaw("rule-x")).contains(md);
    }

    @Test
    @DisplayName("readRaw on unknown name returns Optional.empty")
    void readRaw_unknown(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());
        assertThat(store.readRaw("never-existed")).isEmpty();
    }

    @Test
    @DisplayName("delete removes the file and returns true; second delete returns false")
    void delete_idempotent(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());
        store.save("rule-x", "---\ntype: instruction\n---\nbody\n");

        assertThat(store.delete("rule-x")).isTrue();
        assertThat(store.delete("rule-x")).isFalse();
        assertThat(store.loadAll()).isEmpty();
    }

    @Test
    @DisplayName("save with malformed markdown throws BEFORE touching the filesystem")
    void save_malformed_failsFast(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());

        assertThatThrownBy(() -> store.save("rule-x", "no frontmatter"))
                .isInstanceOf(IllegalArgumentException.class);

        // No file leaked onto disk
        assertThat(store.listNames()).isEmpty();
    }

    @Test
    @DisplayName("save with bad name (path traversal) is rejected")
    void save_pathTraversal_rejected(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());
        String body = "---\ntype: instruction\n---\nbody\n";

        assertThatThrownBy(() -> store.save("../escape", body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("a/b", body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save("", body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("save overwrites existing rule of the same name")
    void save_overwritesExisting(@TempDir Path tmp) {
        var store = new FilesystemHarnessRuleStore(tmp.toString());
        store.save("rule-x", "---\ntype: instruction\n---\nfirst\n");
        store.save("rule-x", "---\ntype: instruction\n---\nsecond\n");

        List<HarnessRule> rules = store.loadAll();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).content()).isEqualTo("second");
    }

    @Test
    @DisplayName("non-md files in directory are ignored")
    void nonMdFiles_ignored(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("README.txt"), "not a rule");
        Files.writeString(tmp.resolve("rule.md"), "---\ntype: instruction\n---\nbody\n");

        var store = new FilesystemHarnessRuleStore(tmp.toString());
        assertThat(store.listNames()).containsExactly("rule");
    }
}

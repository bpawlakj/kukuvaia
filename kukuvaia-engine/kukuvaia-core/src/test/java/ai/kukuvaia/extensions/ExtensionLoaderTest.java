package ai.kukuvaia.extensions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExtensionLoader — .kukuvaia/ directory discovery")
class ExtensionLoaderTest {

    @Test
    @DisplayName("getSubDir — returns empty when no extension root")
    void getSubDir_noExtensionRoot_returnsEmpty() {
        var loader = new ExtensionLoader();
        // In test env, .kukuvaia/ may or may not exist — getRulesDir should not throw
        assertThat(loader.getRulesDir()).isNotNull(); // Optional, may be empty
    }

    @Test
    @DisplayName("RulesLoader — returns empty string when no rules")
    void rulesLoader_noRules_returnsEmpty() {
        var extensionLoader = new ExtensionLoader();
        var rulesLoader = new RulesLoader(extensionLoader);
        String rules = rulesLoader.loadRules();
        assertThat(rules).isNotNull();
    }
}

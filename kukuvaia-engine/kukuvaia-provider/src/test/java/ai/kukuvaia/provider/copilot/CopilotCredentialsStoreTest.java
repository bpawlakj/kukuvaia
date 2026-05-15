package ai.kukuvaia.provider.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CopilotCredentialsStore — chmod 600 persistence")
class CopilotCredentialsStoreTest {

    @TempDir Path tempDir;
    private CopilotCredentialsStore store;
    private Path credentialsFile;

    @BeforeEach
    void setUp() {
        credentialsFile = tempDir.resolve("credentials.json");
        store = new CopilotCredentialsStore(new ObjectMapper(), credentialsFile.toString());
    }

    @Test
    @DisplayName("load — file missing — returns empty map")
    void load_missingFile_returnsEmpty() {
        assertThat(store.load()).isEmpty();
        assertThat(store.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("put + load — round-trips value")
    void put_thenLoad_returnsValue() {
        store.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc123");

        assertThat(store.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN)).contains("gho_abc123");
    }

    @Test
    @DisplayName("write — applies POSIX 600 permissions")
    void write_appliesOwnerOnlyPerms() throws Exception {
        store.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(credentialsFile);
        assertThat(perms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    @DisplayName("putAll with null value — removes existing key")
    void putAll_nullValue_removesKey() {
        store.put(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc");
        store.putAll(java.util.Collections.singletonMap(
                CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, null));

        assertThat(store.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN)).isEmpty();
    }

    @Test
    @DisplayName("removePrefix — wipes all keys with prefix, leaves others")
    void removePrefix_wipesMatchingKeysOnly() {
        store.putAll(Map.of(
                CopilotCredentialsStore.GITHUB_OAUTH_TOKEN, "gho_abc",
                CopilotCredentialsStore.GITHUB_LOGIN, "octocat",
                "other.key", "keep-me"));

        store.removePrefix("github.");

        assertThat(store.get(CopilotCredentialsStore.GITHUB_OAUTH_TOKEN)).isEmpty();
        assertThat(store.get(CopilotCredentialsStore.GITHUB_LOGIN)).isEmpty();
        assertThat(store.get("other.key")).contains("keep-me");
    }

    @Test
    @DisplayName("default path — when override is null — falls back to ~/.kukuvaia/credentials.json")
    void defaultPath_resolvesToUserHome() {
        CopilotCredentialsStore defaultStore = new CopilotCredentialsStore(new ObjectMapper(), null);

        Path expected = Path.of(System.getProperty("user.home"), ".kukuvaia", "credentials.json");
        assertThat(defaultStore.getFilePath()).isEqualTo(expected);
    }
}

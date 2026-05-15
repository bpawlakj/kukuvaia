package ai.kukuvaia.provider.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * On-disk credential store for long-lived OAuth tokens (GitHub Copilot device flow).
 *
 * <p>Storage: {@code ~/.kukuvaia/credentials.json} with POSIX permissions {@code 0600}
 * (owner read/write only). The standards file
 * {@code .maister/docs/standards/security/credentials.md} mandates this guard for any
 * future on-disk OAuth token. Short-lived API tokens (the 25-min Copilot bearer) stay
 * in-memory in {@link CopilotTokenProvider} and never touch disk.
 *
 * <p>File format — flat JSON map of dotted keys to values:
 * <pre>
 * {
 *   "github.oauth_token": "gho_xxx...",
 *   "github.login":       "octocat",
 *   "github.saved_at":    "2026-05-11T12:34:56Z"
 * }
 * </pre>
 * Flat-key layout keeps the file forward-compatible: adding a new provider means
 * adding new keys, not changing structure.
 */
@Component
public class CopilotCredentialsStore {

    private static final Logger log = LoggerFactory.getLogger(CopilotCredentialsStore.class);

    static final String GITHUB_OAUTH_TOKEN = "github.oauth_token";
    static final String GITHUB_LOGIN = "github.login";
    static final String GITHUB_SAVED_AT = "github.saved_at";

    private static final Set<PosixFilePermission> OWNER_RW = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final ObjectMapper objectMapper;
    private final Path filePath;

    public CopilotCredentialsStore(
            ObjectMapper objectMapper,
            @Value("${kukuvaia.providers.copilot.credentials-path:#{null}}") String overridePath) {
        this.objectMapper = objectMapper;
        this.filePath = resolvePath(overridePath);
    }

    private static Path resolvePath(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".kukuvaia", "credentials.json");
    }

    /** Path used by this store (visible for tests). */
    Path getFilePath() {
        return filePath;
    }

    /**
     * Load the credentials file. Returns an empty map if the file does not exist.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, String> load() {
        if (!Files.exists(filePath)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(filePath.toFile(), Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (v != null) out.put(k, v.toString());
            });
            return out;
        } catch (IOException e) {
            log.warn("Failed to read credentials file {} — treating as empty: {}",
                    filePath, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** Return a single key if present. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(load().get(key));
    }

    /**
     * Merge entries into the file and rewrite atomically with {@code 0600} permissions.
     * Keys with a null value are removed.
     */
    public synchronized void putAll(Map<String, String> entries) {
        Map<String, String> current = load();
        entries.forEach((k, v) -> {
            if (v == null) current.remove(k);
            else current.put(k, v);
        });
        write(current);
    }

    /** Convenience — set a single key. */
    public void put(String key, String value) {
        putAll(Map.of(key, value));
    }

    /** Remove all keys with the given prefix (e.g. {@code "github."}). */
    public synchronized void removePrefix(String prefix) {
        Map<String, String> current = load();
        boolean changed = current.keySet().removeIf(k -> k.startsWith(prefix));
        if (changed) write(current);
    }

    /** Delete the credentials file entirely (rare — full wipe). */
    public synchronized void deleteFile() {
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete credentials file {}: {}", filePath, e.getMessage());
        }
    }

    private void write(Map<String, String> map) {
        try {
            ensureParentDirectory();
            Path temp = Files.createTempFile(filePath.getParent(), ".credentials-", ".tmp");
            try {
                applyOwnerOnlyPermissions(temp);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), map);
                Files.move(temp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                applyOwnerOnlyPermissions(filePath);
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
        } catch (IOException e) {
            throw new CredentialsIoException("Failed to write credentials file " + filePath, e);
        }
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = filePath.getParent();
        if (parent == null) return;
        if (!Files.exists(parent)) {
            Files.createDirectories(parent);
            applyOwnerOnlyDirPermissions(parent);
        }
    }

    private void applyOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_RW);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows) — best-effort, skip.
        } catch (IOException e) {
            log.warn("Could not chmod 600 {}: {}", path, e.getMessage());
        }
    }

    private void applyOwnerOnlyDirPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem — skip.
        } catch (IOException e) {
            log.warn("Could not chmod 700 {}: {}", path, e.getMessage());
        }
    }

    public static class CredentialsIoException extends RuntimeException {
        public CredentialsIoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

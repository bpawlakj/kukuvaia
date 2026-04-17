package ai.kukuvaia.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Validates credentials file permissions on startup.
 * Covers: Finding #22 (credentials file permissions).
 *
 * Checks ~/.kukuvaia/credentials.json is chmod 600 (owner read/write only).
 * Warns or fails if file is world-readable.
 */
@Component
public class CredentialsFileGuard {

    private static final Logger log = LoggerFactory.getLogger(CredentialsFileGuard.class);
    private static final Set<PosixFilePermission> SAFE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    @Value("${kukuvaia.credentials.path:${user.home}/.kukuvaia/credentials.json}")
    private String credentialsPath;

    @Value("${kukuvaia.security.strict-permissions:true}")
    private boolean strictPermissions;

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        Path path = Path.of(credentialsPath);
        if (!Files.exists(path)) {
            log.debug("Credentials file not found at {} — no validation needed", path);
            return;
        }

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);

            boolean hasGroupOrOther = perms.stream().anyMatch(p ->
                    p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));

            if (hasGroupOrOther) {
                String msg = "Credentials file %s has unsafe permissions: %s. Expected: owner-only (600)."
                        .formatted(path, perms);
                if (strictPermissions) {
                    log.error(msg + " Fix with: chmod 600 " + path);
                    throw new SecurityException(msg);
                } else {
                    log.warn(msg + " Continuing in non-strict mode.");
                }
            } else {
                log.info("Credentials file permissions OK: {}", path);
            }
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows) — skip
            log.debug("POSIX permissions not supported, skipping credentials file check");
        } catch (IOException e) {
            log.warn("Could not check credentials file permissions: {}", e.getMessage());
        }
    }
}

package ai.kukuvaia.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Enforces file system boundaries for daemon file/git tools.
 * Prevents path traversal, symlink escapes, and access outside configured workspaces.
 *
 * All file operations MUST call {@link #validatePath(String)} before accessing the filesystem.
 */
@Component
public class WorkspaceSandbox {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSandbox.class);

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "rm", "rmdir", "curl", "wget", "ssh", "scp", "sudo", "su",
            "chmod", "chown", "kill", "killall", "pkill",
            "nc", "ncat", "netcat", "dd", "mkfs", "mount", "umount",
            "shutdown", "reboot", "poweroff", "systemctl", "service"
    );

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "git", "gradle", "./gradlew", "gradlew", "mvn", "./mvnw",
            "npm", "npx", "node", "pnpm", "yarn", "bun",
            "ls", "cat", "head", "tail", "wc", "sort", "uniq",
            "grep", "find", "diff", "echo", "pwd", "date",
            "java", "javac", "kotlin", "kotlinc",
            "python", "python3", "pip", "uv",
            "go", "cargo", "rustc"
    );

    private static final Set<String> BLOCKED_GIT_SUBCOMMANDS = Set.of(
            "push", "reset", "clean", "checkout"
    );

    private final List<Path> allowedWorkspaces;

    public WorkspaceSandbox(@Value("${kukuvaia.workspace.paths:#{null}}") List<String> paths) {
        if (paths != null && !paths.isEmpty()) {
            this.allowedWorkspaces = paths.stream()
                    .map(p -> Path.of(p).toAbsolutePath().normalize())
                    .toList();
        } else {
            // Default: current working directory
            this.allowedWorkspaces = List.of(Path.of("").toAbsolutePath().normalize());
        }
        log.info("WorkspaceSandbox initialized: {}", allowedWorkspaces);
    }

    /**
     * Validate that a file path is within allowed workspaces.
     * Resolves symlinks to detect escapes.
     *
     * @throws SecurityException if path is outside workspace or uses traversal
     */
    public Path validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new SecurityException("File path cannot be empty");
        }

        // Block obvious traversal attempts before resolving
        if (path.contains("..")) {
            throw new SecurityException("Path traversal blocked: '..' not allowed in path: " + path);
        }

        Path resolved = Path.of(path).toAbsolutePath().normalize();

        // Resolve symlinks to real path (if file exists)
        try {
            if (Files.exists(resolved)) {
                resolved = resolved.toRealPath();
            }
        } catch (IOException e) {
            // File doesn't exist yet (write operation) — use normalized path
        }

        for (Path workspace : allowedWorkspaces) {
            if (resolved.startsWith(workspace)) {
                return resolved;
            }
        }

        throw new SecurityException(
                "Access denied: path '%s' is outside allowed workspaces %s".formatted(path, allowedWorkspaces));
    }

    /**
     * Validate a bash command against allowlist.
     *
     * @throws SecurityException if command is blocked or not in allowlist
     */
    public List<String> validateCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new SecurityException("Command cannot be empty");
        }

        // Split into executable + args (no shell — ProcessBuilder semantics)
        String[] parts = command.trim().split("\\s+");
        String executable = parts[0];

        // Extract base name (handle paths like ./gradlew)
        String baseName = Path.of(executable).getFileName().toString();

        // Check blocked list first
        if (BLOCKED_COMMANDS.contains(baseName)) {
            throw new SecurityException("Blocked command: " + baseName);
        }

        // Check allowed list
        if (!ALLOWED_COMMANDS.contains(baseName) && !ALLOWED_COMMANDS.contains(executable)) {
            throw new SecurityException(
                    "Command not in allowlist: '%s'. Allowed: %s".formatted(baseName, ALLOWED_COMMANDS));
        }

        // Git-specific subcommand guard
        if ("git".equals(baseName) && parts.length > 1) {
            String subcommand = parts[1];
            if (BLOCKED_GIT_SUBCOMMANDS.contains(subcommand)) {
                // Allow `git checkout <branch>` but block `git checkout .` and `git checkout -- .`
                if ("checkout".equals(subcommand)) {
                    for (int i = 2; i < parts.length; i++) {
                        if (".".equals(parts[i]) || "--".equals(parts[i])) {
                            throw new SecurityException("Blocked: git checkout with discard (use git stash instead)");
                        }
                    }
                    // git checkout <branch-name> is OK
                } else {
                    throw new SecurityException("Blocked git subcommand: git " + subcommand);
                }
            }
        }

        return List.of(parts);
    }

    /**
     * Check if a path would be valid (without throwing).
     */
    public boolean isPathAllowed(String path) {
        try {
            validatePath(path);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}

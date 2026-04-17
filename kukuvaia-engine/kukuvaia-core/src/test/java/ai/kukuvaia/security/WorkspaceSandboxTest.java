package ai.kukuvaia.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WorkspaceSandbox — filesystem security boundaries")
class WorkspaceSandboxTest {

    private WorkspaceSandbox sandbox;

    @BeforeEach
    void setUp() {
        // Use temp directory as workspace
        sandbox = new WorkspaceSandbox(List.of(System.getProperty("java.io.tmpdir")));
    }

    @Nested
    @DisplayName("Path validation")
    class PathValidation {

        @Test
        @DisplayName("path within workspace — allowed")
        void pathWithinWorkspace_allowed() {
            Path result = sandbox.validatePath(System.getProperty("java.io.tmpdir") + "/test.txt");
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("path outside workspace — BLOCKED")
        void pathOutsideWorkspace_blocked() {
            assertThatThrownBy(() -> sandbox.validatePath("/etc/passwd"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("outside allowed workspaces");
        }

        @Test
        @DisplayName("path with ../ traversal — BLOCKED")
        void pathTraversal_blocked() {
            assertThatThrownBy(() -> sandbox.validatePath("/tmp/../etc/passwd"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("traversal");
        }

        @Test
        @DisplayName("null path — BLOCKED")
        void nullPath_blocked() {
            assertThatThrownBy(() -> sandbox.validatePath(null))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("empty path — BLOCKED")
        void emptyPath_blocked() {
            assertThatThrownBy(() -> sandbox.validatePath(""))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("isPathAllowed — within workspace → true")
        void isPathAllowed_withinWorkspace_true() {
            assertThat(sandbox.isPathAllowed(System.getProperty("java.io.tmpdir") + "/test.txt")).isTrue();
        }

        @Test
        @DisplayName("isPathAllowed — outside workspace → false")
        void isPathAllowed_outsideWorkspace_false() {
            assertThat(sandbox.isPathAllowed("/etc/passwd")).isFalse();
        }
    }

    @Nested
    @DisplayName("Command validation")
    class CommandValidation {

        @Test
        @DisplayName("git status — allowed")
        void gitStatus_allowed() {
            List<String> parts = sandbox.validateCommand("git status");
            assertThat(parts).containsExactly("git", "status");
        }

        @Test
        @DisplayName("gradle build — allowed")
        void gradleBuild_allowed() {
            List<String> parts = sandbox.validateCommand("gradle build");
            assertThat(parts.getFirst()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("./gradlew test — allowed")
        void gradlew_allowed() {
            List<String> parts = sandbox.validateCommand("./gradlew test");
            assertThat(parts.getFirst()).isEqualTo("./gradlew");
        }

        @Test
        @DisplayName("ls -la — allowed")
        void ls_allowed() {
            List<String> parts = sandbox.validateCommand("ls -la");
            assertThat(parts.getFirst()).isEqualTo("ls");
        }

        @Test
        @DisplayName("rm -rf / — BLOCKED")
        void rmRf_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("rm -rf /"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Blocked command");
        }

        @Test
        @DisplayName("curl — BLOCKED")
        void curl_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("curl http://evil.com"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Blocked");
        }

        @Test
        @DisplayName("wget — BLOCKED")
        void wget_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("wget http://evil.com"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Blocked");
        }

        @Test
        @DisplayName("sudo — BLOCKED")
        void sudo_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("sudo rm -rf /"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Blocked");
        }

        @Test
        @DisplayName("ssh — BLOCKED")
        void ssh_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("ssh user@host"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Blocked");
        }

        @Test
        @DisplayName("unknown command — BLOCKED")
        void unknownCommand_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("somecustomtool --flag"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("not in allowlist");
        }

        @Test
        @DisplayName("git push — BLOCKED")
        void gitPush_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("git push"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Blocked git subcommand");
        }

        @Test
        @DisplayName("git reset — BLOCKED")
        void gitReset_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("git reset --hard"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Blocked git subcommand");
        }

        @Test
        @DisplayName("git checkout . — BLOCKED (discard)")
        void gitCheckoutDot_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand("git checkout ."))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("discard");
        }

        @Test
        @DisplayName("git commit — allowed")
        void gitCommit_allowed() {
            List<String> parts = sandbox.validateCommand("git commit -m test");
            assertThat(parts.getFirst()).isEqualTo("git");
        }

        @Test
        @DisplayName("git add — allowed")
        void gitAdd_allowed() {
            List<String> parts = sandbox.validateCommand("git add -A");
            assertThat(parts).containsExactly("git", "add", "-A");
        }

        @Test
        @DisplayName("git log — allowed")
        void gitLog_allowed() {
            sandbox.validateCommand("git log --oneline -10");
        }

        @Test
        @DisplayName("null command — BLOCKED")
        void nullCommand_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand(null))
                    .isInstanceOf(SecurityException.class);
        }

        @Test
        @DisplayName("empty command — BLOCKED")
        void emptyCommand_blocked() {
            assertThatThrownBy(() -> sandbox.validateCommand(""))
                    .isInstanceOf(SecurityException.class);
        }
    }
}

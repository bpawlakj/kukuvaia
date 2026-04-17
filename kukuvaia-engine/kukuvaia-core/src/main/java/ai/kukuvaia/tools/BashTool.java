package ai.kukuvaia.tools;

import ai.kukuvaia.security.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sandboxed bash command execution for daemon tasks.
 * Uses {@link ProcessBuilder} (no shell expansion).
 * Commands validated against allowlist via {@link WorkspaceSandbox}.
 */
@Component
public class BashTool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_LENGTH = 50_000;

    private final WorkspaceSandbox sandbox;

    public BashTool(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(description = "Run a shell command (sandboxed). Only allowed commands: git, gradle, npm, ls, grep, find, etc.")
    public Map<String, Object> bashRun(
            @ToolParam(description = "Command to execute") String command) {
        try {
            List<String> parts = sandbox.validateCommand(command);

            ProcessBuilder pb = new ProcessBuilder(parts)
                    .redirectErrorStream(true);

            // Run in first allowed workspace directory
            Path workDir = Path.of("").toAbsolutePath();
            pb.directory(workDir.toFile());

            log.info("Executing: {} (in {})", command, workDir);

            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return Map.of("error", "Command timed out after " + TIMEOUT_SECONDS + "s", "command", command);
            }

            String output = new String(process.getInputStream().readAllBytes());
            if (output.length() > MAX_OUTPUT_LENGTH) {
                output = output.substring(0, MAX_OUTPUT_LENGTH) + "\n... (truncated)";
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return Map.of("command", command, "exitCode", exitCode, "output", output);
            }

            return Map.of("command", command, "exitCode", 0, "output", output);

        } catch (SecurityException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            return Map.of("error", "Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("error", "Command interrupted");
        }
    }
}

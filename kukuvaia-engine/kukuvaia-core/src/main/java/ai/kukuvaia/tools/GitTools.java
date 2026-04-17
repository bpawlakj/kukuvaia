package ai.kukuvaia.tools;

import ai.kukuvaia.security.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Git tools for daemon coding tasks.
 * Read-only operations + commit. No push, no reset, no checkout.
 * Commands run via {@link BashTool} with {@link WorkspaceSandbox} enforcement.
 */
@Component
public class GitTools {

    private static final Logger log = LoggerFactory.getLogger(GitTools.class);

    private final BashTool bashTool;

    public GitTools(BashTool bashTool) {
        this.bashTool = bashTool;
    }

    @Tool(description = "Show git status of the working directory.")
    public Map<String, Object> gitStatus() {
        return bashTool.bashRun("git status --short");
    }

    @Tool(description = "Show git diff of uncommitted changes.")
    public Map<String, Object> gitDiff() {
        return bashTool.bashRun("git diff");
    }

    @Tool(description = "Show git diff for a specific file.")
    public Map<String, Object> gitDiffFile(
            @ToolParam(description = "File path to diff") String path) {
        return bashTool.bashRun("git diff " + sanitizeArg(path));
    }

    @Tool(description = "Show recent git log entries.")
    public Map<String, Object> gitLog(
            @ToolParam(description = "Number of entries to show") int count) {
        int safeCount = Math.min(Math.max(count, 1), 50);
        return bashTool.bashRun("git log --oneline -" + safeCount);
    }

    @Tool(description = "Stage and commit changes with a message. Does NOT push.")
    public Map<String, Object> gitCommit(
            @ToolParam(description = "Commit message") String message) {
        // Stage all tracked changes
        var stageResult = bashTool.bashRun("git add -A");
        if (stageResult.containsKey("error")) return stageResult;

        String safeMessage = message.replace("\"", "'").replace("$", "").replace("`", "'");
        return bashTool.bashRun("git commit -m \"" + safeMessage + "\"");
    }

    private String sanitizeArg(String arg) {
        // Remove shell metacharacters
        return arg.replaceAll("[;&|`$(){}\\[\\]<>!#]", "");
    }
}

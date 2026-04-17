package ai.kukuvaia.tools;

import ai.kukuvaia.security.WorkspaceSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File system tools for daemon coding tasks.
 * All operations are sandboxed via {@link WorkspaceSandbox}.
 */
@Component
public class FileTools {

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);
    private static final long MAX_READ_SIZE = 1_000_000; // 1MB

    private final WorkspaceSandbox sandbox;

    public FileTools(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(description = "Read file content. Returns text content of the file.")
    public Map<String, Object> readFile(
            @ToolParam(description = "Path to the file") String path) {
        try {
            Path validated = sandbox.validatePath(path);
            if (!Files.exists(validated)) {
                return Map.of("error", "File not found: " + path);
            }
            if (Files.size(validated) > MAX_READ_SIZE) {
                return Map.of("error", "File too large (>1MB): " + path);
            }
            String content = Files.readString(validated);
            return Map.of("path", path, "content", content, "lines", content.lines().count());
        } catch (SecurityException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            return Map.of("error", "Failed to read file: " + e.getMessage());
        }
    }

    @Tool(description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does.")
    public Map<String, Object> writeFile(
            @ToolParam(description = "Path to the file") String path,
            @ToolParam(description = "Content to write") String content) {
        try {
            Path validated = sandbox.validatePath(path);
            Files.createDirectories(validated.getParent());
            Files.writeString(validated, content);
            log.info("Wrote file: {} ({} chars)", path, content.length());
            return Map.of("path", path, "written", content.length());
        } catch (SecurityException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            return Map.of("error", "Failed to write file: " + e.getMessage());
        }
    }

    @Tool(description = "Replace text in a file. Finds oldText and replaces with newText.")
    public Map<String, Object> editFile(
            @ToolParam(description = "Path to the file") String path,
            @ToolParam(description = "Text to find") String oldText,
            @ToolParam(description = "Text to replace with") String newText) {
        try {
            Path validated = sandbox.validatePath(path);
            if (!Files.exists(validated)) {
                return Map.of("error", "File not found: " + path);
            }
            String content = Files.readString(validated);
            if (!content.contains(oldText)) {
                return Map.of("error", "Old text not found in file");
            }
            String updated = content.replace(oldText, newText);
            Files.writeString(validated, updated);
            log.info("Edited file: {}", path);
            return Map.of("path", path, "status", "edited");
        } catch (SecurityException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            return Map.of("error", "Failed to edit file: " + e.getMessage());
        }
    }

    @Tool(description = "List files matching a glob pattern in a directory.")
    public Map<String, Object> listFiles(
            @ToolParam(description = "Directory path") String directory,
            @ToolParam(description = "Glob pattern (e.g., '*.java', '**/*.kt')") String pattern) {
        try {
            Path validated = sandbox.validatePath(directory);
            if (!Files.isDirectory(validated)) {
                return Map.of("error", "Not a directory: " + directory);
            }
            try (Stream<Path> stream = Files.walk(validated)) {
                var matcher = validated.getFileSystem().getPathMatcher("glob:" + pattern);
                List<String> matches = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(validated.relativize(p)))
                        .map(Path::toString)
                        .limit(500)
                        .toList();
                return Map.of("directory", directory, "pattern", pattern,
                        "matches", matches, "count", matches.size());
            }
        } catch (SecurityException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            return Map.of("error", "Failed to list files: " + e.getMessage());
        }
    }

    @Tool(description = "Search for text pattern in files within a directory.")
    public Map<String, Object> searchContent(
            @ToolParam(description = "Text or regex pattern to search for") String pattern,
            @ToolParam(description = "Directory path to search in") String directory) {
        try {
            Path validated = sandbox.validatePath(directory);
            if (!Files.isDirectory(validated)) {
                return Map.of("error", "Not a directory: " + directory);
            }
            var results = new java.util.ArrayList<Map<String, Object>>();
            try (Stream<Path> stream = Files.walk(validated)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains(".git/"))
                        .limit(1000)
                        .forEach(file -> {
                            try {
                                if (Files.size(file) > MAX_READ_SIZE) return;
                                List<String> lines = Files.readAllLines(file);
                                for (int i = 0; i < lines.size(); i++) {
                                    if (lines.get(i).contains(pattern)) {
                                        results.add(Map.of(
                                                "file", file.toString(),
                                                "line", i + 1,
                                                "content", lines.get(i).trim()));
                                        if (results.size() >= 100) return;
                                    }
                                }
                            } catch (IOException ignored) {}
                        });
            }
            return Map.of("pattern", pattern, "results", results, "count", results.size());
        } catch (SecurityException e) {
            return Map.of("error", e.getMessage());
        } catch (IOException e) {
            return Map.of("error", "Failed to search: " + e.getMessage());
        }
    }
}

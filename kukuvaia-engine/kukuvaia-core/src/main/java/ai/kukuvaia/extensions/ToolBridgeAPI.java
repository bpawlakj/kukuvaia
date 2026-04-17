package ai.kukuvaia.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Low-level bridge API for tool pipelines.
 * Provides filesystem, database, and HTTP primitives.
 *
 * Security: filesystem sandboxed to workspace root, parameterized SQL only.
 */
public class ToolBridgeAPI {

    private static final Logger log = LoggerFactory.getLogger(ToolBridgeAPI.class);
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of(
            "md", "yaml", "yml", "json", "xml", "csv", "txt", "html");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Path workspaceRoot;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient restClient;

    public ToolBridgeAPI(Path workspaceRoot, JdbcTemplate jdbcTemplate) {
        this.workspaceRoot = workspaceRoot;
        this.jdbcTemplate = jdbcTemplate;
        this.restClient = RestClient.create();
    }

    // === Filesystem ===

    public String readFile(String path) {
        try {
            return Files.readString(validate(path));
        } catch (Exception e) { return error("readFile", e); }
    }

    public String writeFile(String path, String content) {
        try {
            Path resolved = validate(path);
            if (content.getBytes().length > MAX_FILE_SIZE) return "{\"error\":\"File too large (max 5MB)\"}";
            if (Files.exists(resolved)) {
                Files.copy(resolved, resolved.resolveSibling(resolved.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content);
            return "{\"ok\":true,\"path\":\"%s\"}".formatted(path);
        } catch (Exception e) { return error("writeFile", e); }
    }

    public String editFile(String path, String oldText, String newText) {
        try {
            Path resolved = validate(path);
            String content = Files.readString(resolved);
            if (!content.contains(oldText)) return "{\"error\":\"Text not found\"}";
            Files.copy(resolved, resolved.resolveSibling(resolved.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(resolved, content.replace(oldText, newText));
            return "{\"ok\":true}";
        } catch (Exception e) { return error("editFile", e); }
    }

    public String findFiles(String glob) {
        try {
            List<String> results = new ArrayList<>();
            var matcher = workspaceRoot.getFileSystem().getPathMatcher("glob:" + glob);
            try (Stream<Path> files = Files.walk(workspaceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(this::allowedExt)
                        .filter(p -> matcher.matches(workspaceRoot.relativize(p)))
                        .forEach(p -> results.add(workspaceRoot.relativize(p).toString()));
            }
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) { return error("findFiles", e); }
    }

    public String searchFiles(String query, String glob) {
        try {
            List<Map<String, String>> results = new ArrayList<>();
            try (Stream<Path> files = Files.walk(workspaceRoot)) {
                files.filter(Files::isRegularFile).filter(this::allowedExt)
                        .filter(p -> glob == null || glob.isBlank() || matchGlob(p, glob))
                        .forEach(p -> {
                            try {
                                String c = Files.readString(p);
                                if (c.contains(query)) {
                                    int i = c.indexOf(query);
                                    results.add(Map.of("path", workspaceRoot.relativize(p).toString(),
                                            "snippet", c.substring(Math.max(0, i - 40),
                                                    Math.min(c.length(), i + query.length() + 40))));
                                }
                            } catch (IOException ignored) {}
                        });
            }
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) { return error("searchFiles", e); }
    }

    public boolean fileExists(String path) {
        try { return Files.exists(validate(path)); }
        catch (Exception e) { return false; }
    }

    // === Database ===

    public String dbQuery(String sql, String paramsJson) {
        try {
            Object[] params = paramsJson != null && !paramsJson.isBlank() && !"[]".equals(paramsJson)
                    ? objectMapper.readValue(paramsJson, Object[].class) : new Object[0];
            return objectMapper.writeValueAsString(jdbcTemplate.queryForList(sql, params));
        } catch (Exception e) { return error("dbQuery", e); }
    }

    public String dbExecute(String sql, String paramsJson) {
        try {
            Object[] params = paramsJson != null && !paramsJson.isBlank() && !"[]".equals(paramsJson)
                    ? objectMapper.readValue(paramsJson, Object[].class) : new Object[0];
            int rows = jdbcTemplate.update(sql, params);
            return "{\"ok\":true,\"rows\":%d}".formatted(rows);
        } catch (Exception e) { return error("dbExecute", e); }
    }

    // === HTTP ===

    public String httpGet(String url) {
        try {
            return restClient.get().uri(url)
                    .header("User-Agent", "Kukuvaia/1.0")
                    .retrieve().body(String.class);
        } catch (Exception e) { return error("httpGet", e); }
    }

    public String httpGetWithHeader(String url, String headerName, String headerValue) {
        try {
            return restClient.get().uri(url)
                    .header("User-Agent", "Kukuvaia/1.0")
                    .header(headerName, headerValue)
                    .retrieve().body(String.class);
        } catch (Exception e) { return error("httpGetWithHeader", e); }
    }

    public String httpPost(String url, String body) {
        try {
            return restClient.post().uri(url)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Kukuvaia/1.0")
                    .body(body)
                    .retrieve().body(String.class);
        } catch (Exception e) { return error("httpPost", e); }
    }

    // === Logging ===

    public void log(String msg) { log.info("[Tool] {}", msg); }

    // === Helpers ===

    private Path validate(String path) {
        Path resolved = workspaceRoot.resolve(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(workspaceRoot))
            throw new SecurityException("Path traversal blocked: " + path);
        return resolved;
    }

    private boolean allowedExt(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && ALLOWED_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    private boolean matchGlob(Path p, String glob) {
        return workspaceRoot.getFileSystem().getPathMatcher("glob:" + glob)
                .matches(workspaceRoot.relativize(p));
    }

    private String error(String op, Exception e) {
        log.warn("Bridge.{} failed: {}", op, e.getMessage());
        return "{\"error\":\"%s\"}".formatted(e.getMessage().replace("\"", "\\\""));
    }
}

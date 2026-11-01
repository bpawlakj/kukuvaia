package ai.kukuvaia.agent.daemon.exec;

import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.PromptRenderer;
import ai.kukuvaia.mcp.McpClientRegistry;
import ai.kukuvaia.security.ErrorSanitizer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code kind: mcp} — one typed tool call on a remote MCP server: {@code target: {connection, tool}}
 * names an {@code mcp_connections} row and one of its tools. Deterministic: no local LLM, zero tokens on
 * our account (whatever the remote side spends is out of scope and not charged).
 *
 * <p>Arguments come only from the job's {@code args:} map: string values are templates over declared,
 * sanitized fields (rendered flattened), numbers/booleans are literal. The result is treated as data:
 * text content is redacted ({@link ErrorSanitizer}) and capped before it is stored; a tool-level error
 * becomes a FAILED run with a fixed message. The runtime's deadline cancels the call (TIMEOUT).
 */
@Component
public class McpToolExecutor implements DaemonExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);
    static final int MAX_RESULT_CHARS = 64 * 1024;

    static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final int MAX_LISTED = 20;

    private final ObjectProvider<McpClientRegistry> registry;
    private final ErrorSanitizer sanitizer;
    private final ObjectProvider<ObjectMapper> objectMapper;
    private final ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties properties;

    public McpToolExecutor(ObjectProvider<McpClientRegistry> registry, ErrorSanitizer sanitizer,
                           ObjectProvider<ObjectMapper> objectMapper,
                           ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties properties) {
        this.registry = registry;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    private String notAllowed(Target t) {
        String key = t.connection() + "/" + t.tool();
        return properties.getMcpAllowedTools().contains(key) ? null
                : "MCP tool '" + key + "' is not in kukuvaia.daemon.mcp-allowed-tools (kind: mcp is deny-by-default)";
    }

    private static String listed(java.util.Collection<String> names) {
        return names.size() <= MAX_LISTED ? names.toString()
                : names.stream().limit(MAX_LISTED).toList() + " … (+" + (names.size() - MAX_LISTED) + " more)";
    }

    @Override
    public JobKind kind() {
        return JobKind.MCP;
    }

    @Override
    public Optional<String> validate(DaemonJobDefinition definition) {
        Target t = Target.parse(definition.target());
        if (t == null) return Optional.of("mcp target must be {connection, tool}");
        String denied = notAllowed(t);
        if (denied != null) return Optional.of(denied);
        McpClientRegistry clients = registry.getIfAvailable();
        if (clients == null) {
            return Optional.of("MCP client is disabled (spring.ai.mcp.client.enabled=false) — kind: mcp unavailable");
        }
        if (clients.find(t.connection()).isEmpty()) {
            return Optional.of("no MCP connection named '" + t.connection() + "' (available: " + listed(clients.names()) + ")");
        }
        return Optional.empty(); // the tool is checked per run — the server may be down at startup
    }

    @Override
    public ExecutionOutcome execute(JobInvocation invocation, JobLimits limits) {
        Target t = Target.parse(invocation.target());
        if (t == null) throw new IllegalArgumentException("mcp target must be connection/tool");
        String denied = notAllowed(t);
        if (denied != null) throw new IllegalStateException(denied);
        McpClientRegistry clients = registry.getIfAvailable();
        if (clients == null) throw new IllegalStateException("MCP client is disabled");
        McpSyncClient client = clients.find(t.connection())
                .orElseThrow(() -> new IllegalArgumentException("No MCP connection named '" + t.connection() + "'"));

        if (!client.isInitialized()) client.initialize();
        List<String> tools = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
        if (!tools.contains(t.tool())) {
            throw new IllegalArgumentException("MCP connection '" + t.connection() + "' has no tool '" + t.tool()
                    + "' (available: " + listed(tools) + ")");
        }

        Map<String, Object> arguments = render(invocation.args(), invocation.fields());
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(t.tool(), arguments));
        if (Boolean.TRUE.equals(result.isError())) {
            log.warn("MCP tool {}/{} returned an error for job '{}': {}", t.connection(), t.tool(), invocation.jobName(),
                    sanitizer.sanitize(text(result)));
            throw new IllegalStateException("MCP tool " + t.connection() + "/" + t.tool() + " returned an error");
        }
        // Pre-cap before redaction (bounded work), redact, then cap to the stored size.
        String raw = text(result);
        if (raw.length() > 2 * MAX_RESULT_CHARS) raw = raw.substring(0, 2 * MAX_RESULT_CHARS);
        String text = cap(sanitizer.redact(raw));
        JsonNode output = null;
        if (result.structuredContent() != null && objectMapper.getIfAvailable() != null) {
            try {
                JsonNode tree = objectMapper.getObject().valueToTree(result.structuredContent());
                if (tree.toString().length() <= MAX_OUTPUT_CHARS) {
                    output = tree;
                } else {
                    log.warn("MCP tool {}/{}: structured output above {} chars not stored", t.connection(), t.tool(), MAX_OUTPUT_CHARS);
                }
            } catch (RuntimeException e) {
                log.debug("MCP structured content not convertible: {}", e.getMessage());
            }
        }
        return new ExecutionOutcome(text.isBlank() ? "(no text content)" : text, output, 0, 0, true,
                "mcp:" + t.connection(), t.tool());
    }

    static Map<String, Object> render(Map<String, Object> template, Map<String, String> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        template.forEach((k, v) -> out.put(k, v instanceof String s ? PromptRenderer.render(s, fields) : v));
        return out;
    }

    private static String text(McpSchema.CallToolResult result) {
        if (result.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent tc && tc.text() != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static String cap(String s) {
        return s.length() <= MAX_RESULT_CHARS ? s : s.substring(0, MAX_RESULT_CHARS) + "\n… (truncated)";
    }

    record Target(String connection, String tool) {
        static Target parse(String target) {
            if (target == null) return null;
            int slash = target.indexOf('/');
            if (slash <= 0 || slash == target.length() - 1) return null;
            return new Target(target.substring(0, slash), target.substring(slash + 1));
        }
    }
}

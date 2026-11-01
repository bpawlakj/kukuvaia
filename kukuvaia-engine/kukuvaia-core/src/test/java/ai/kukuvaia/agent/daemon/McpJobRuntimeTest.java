package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonJobRegistry;
import ai.kukuvaia.agent.daemon.exec.McpToolExecutor;
import ai.kukuvaia.mcp.McpClientRegistry;
import ai.kukuvaia.security.ErrorSanitizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("kind: mcp — typed tool call on a remote MCP server under daemon guards")
class McpJobRuntimeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private McpSyncClient client;
    private DaemonRuntime runtime;
    private DaemonJobRegistry registry;
    private final DaemonRuntimeTest.FakeStore store = new DaemonRuntimeTest.FakeStore();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/mcp", this::handle);
        server.start();
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.getAddress().getPort()).build())
                .requestTimeout(Duration.ofSeconds(10)).build();

        var beans = new StaticListableBeanFactory();
        beans.addBean("registry", new McpClientRegistry(Map.of("toolkit", client)));
        beans.addBean("mapper", JSON);
        var executor = new McpToolExecutor(beans.getBeanProvider(McpClientRegistry.class), new ErrorSanitizer(),
                beans.getBeanProvider(ObjectMapper.class));
        registry = new DaemonJobRegistry(List.of(executor), List.of(
                Map.entry("echo.yaml", """
                        name: toolkit-echo
                        kind: mcp
                        target: { connection: toolkit, tool: echo }
                        input: { fields: [q] }
                        args: { q: "{{fields.q}}", limit: 5, strict: true }
                        limits: { timeout: 5s }
                        """),
                Map.entry("slow.yaml", "name: toolkit-slow\nkind: mcp\ntarget: { connection: toolkit, tool: slow }\nlimits: { timeout: 1s }\n"),
                Map.entry("fail.yaml", "name: toolkit-fail\nkind: mcp\ntarget: { connection: toolkit, tool: fail }\n"),
                Map.entry("nosuch.yaml", "name: toolkit-nosuch\nkind: mcp\ntarget: { connection: toolkit, tool: nosuch }\n"),
                Map.entry("ghost.yaml", "name: ghost-job\nkind: mcp\ntarget: { connection: ghost, tool: echo }\n")));
        var properties = new DaemonProperties();
        properties.setCancelGraceMillis(500);
        runtime = new DaemonRuntime(List.of(executor), DaemonRuntimeTest.budget(100_000, new AtomicInteger(), store.order),
                new DaemonScheduleGuard(), store, properties, new ErrorSanitizer(), new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
        client.closeGracefully();
        server.stop(0);
    }

    private DaemonTaskResult run(String job, Map<String, String> fields) {
        DaemonJobDefinition d = registry.find(job).orElseThrow();
        return runtime.runSync(d, new JobRequest(null, fields, "manual", null));
    }

    @Test
    @DisplayName("YAML mcp job → COMPLETED with tool output; args typed, fields flattened; zero tokens")
    void completed() {
        DaemonTaskResult r = run("toolkit-echo", Map.of("q", "hello\nworld"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.COMPLETED);
        assertThat(r.result()).isEqualTo("echo: hello world");
        assertThat(r.tokenUsage()).isZero();
        assertThat(received.get().path("q").asString()).isEqualTo("hello world");
        assertThat(received.get().path("limit").asInt()).isEqualTo(5);
        assertThat(received.get().path("strict").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("slow tool → TIMEOUT at the job deadline")
    void timeout() {
        long start = System.nanoTime();
        DaemonTaskResult r = run("toolkit-slow", Map.of());

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("tool error → FAILED with a fixed message (tool text redacted, not stored); unknown tool → FAILED, clear error")
    void failures() {
        DaemonTaskResult failed = run("toolkit-fail", Map.of());
        assertThat(failed.status()).isEqualTo(DaemonTaskStatus.FAILED);
        assertThat(failed.error()).contains("toolkit/fail returned an error").doesNotContain("hunter2");

        DaemonTaskResult unknown = run("toolkit-nosuch", Map.of());
        assertThat(unknown.status()).isEqualTo(DaemonTaskStatus.FAILED);
        assertThat(unknown.error()).contains("has no tool 'nosuch'").contains("echo");
    }

    @Test
    @DisplayName("unknown connection → job rejected at load with a clear error")
    void unknownConnection() {
        assertThat(registry.find("ghost-job")).isEmpty();
        assertThat(String.join("\n", registry.rejected())).contains("no MCP connection named 'ghost'").contains("toolkit");
    }

    // ── stub MCP server (Streamable HTTP, JSON responses) ──────────────────────────────────────

    private void handle(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
        String method = req.path("method").asString();
        if (!req.has("id")) {
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }
        Object result = switch (method) {
            case "initialize" -> {
                ex.getResponseHeaders().add("Mcp-Session-Id", "s1");
                yield Map.of("protocolVersion", req.path("params").path("protocolVersion").asString(),
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "stub", "version", "1"));
            }
            case "tools/list" -> Map.of("tools", List.of(tool("echo"), tool("slow"), tool("fail")));
            case "tools/call" -> call(req.path("params"));
            default -> Map.of();
        };
        byte[] body = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", JSON.treeToValue(req.get("id"), Object.class), "result", result));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static Map<String, Object> tool(String name) {
        return Map.of("name", name, "description", name, "inputSchema", Map.of("type", "object"));
    }

    private Object call(JsonNode params) {
        String name = params.path("name").asString();
        return switch (name) {
            case "echo" -> {
                received.set(params.path("arguments"));
                yield Map.of("content", List.of(Map.of("type", "text", "text", "echo: " + params.path("arguments").path("q").asString())));
            }
            case "slow" -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                yield Map.of("content", List.of(Map.of("type", "text", "text", "late")));
            }
            default -> Map.of("isError", true, "content", List.of(Map.of("type", "text",
                    "text", "connect failed jdbc:postgresql://db.internal:5432/x?password=hunter2")));
        };
    }
}

package ai.kukuvaia.config;

import ai.kukuvaia.mcp.McpConnection;
import ai.kukuvaia.mcp.McpConnectionsCache;
import ai.kukuvaia.mcp.McpConnectionsRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MCP client transports from DB — Streamable HTTP + SSE auth headers")
class McpClientTransportsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static McpConnectionsCache cache(McpConnection... connections) {
        var repo = mock(McpConnectionsRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(connections));
        var cache = new McpConnectionsCache(repo);
        cache.refresh();
        return cache;
    }

    private static McpConnection connection(String name, String url, String transport) {
        return new McpConnection(UUID.randomUUID(), name, url, "/sse", Map.of("Authorization", "env:STUB_TOKEN"),
                true, null, Instant.now(), Instant.now(), transport, "/mcp");
    }

    private static final McpClientConfig.PerConnectionHeaderCustomizer.EnvResolver ENV =
            name -> "STUB_TOKEN".equals(name) ? "Bearer t0k" : null;

    /** Minimal MCP Streamable HTTP server: JSON responses, Mcp-Session-Id, 404 for unknown sessions. */
    private final class StreamableStub {
        final List<String> authorization = new CopyOnWriteArrayList<>();
        final AtomicInteger initializes = new AtomicInteger();
        final AtomicReference<String> session = new AtomicReference<>();

        void handle(HttpExchange ex) throws IOException {
            authorization.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                ex.close();
                return;
            }
            JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
            String method = req.path("method").asString();
            if ("initialize".equals(method)) {
                String id = "s" + initializes.incrementAndGet();
                session.set(id);
                ex.getResponseHeaders().add("Mcp-Session-Id", id);
                respond(ex, req, Map.of("protocolVersion", req.path("params").path("protocolVersion").asString(),
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "stub", "version", "1")));
                return;
            }
            if (!session.get().equals(ex.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                ex.sendResponseHeaders(404, -1); // session expired / unknown
                ex.close();
                return;
            }
            if (!req.has("id")) { // notification
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            }
            respond(ex, req, Map.of("tools", List.of(Map.of("name", "echo", "description", "echo",
                    "inputSchema", Map.of("type", "object")))));
        }

        private void respond(HttpExchange ex, JsonNode req, Object result) throws IOException {
            byte[] body = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", JSON.treeToValue(req.get("id"), Object.class), "result", result));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        }
    }

    @Test
    @DisplayName("streamable-http row → transport sends the connection's Authorization; re-initializes after a 404 session")
    void streamableHttp() {
        var stub = new StreamableStub();
        server = start("/mcp", stub::handle);
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var cache = cache(connection("toolkit", url, McpConnection.STREAMABLE_HTTP), connection("legacy", url + "/x", McpConnection.SSE));
        var transports = McpClientConfig.streamableHttpTransports(cache.all(), new McpClientConfig.PerConnectionHeaderCustomizer(cache, ENV));

        assertThat(transports).extracting(t -> t.name()).containsExactly("toolkit");
        McpSyncClient client = McpClient.sync(transports.get(0).transport()).requestTimeout(Duration.ofSeconds(5)).build();
        try {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(t -> t.name()).containsExactly("echo");
            assertThat(stub.authorization).isNotEmpty().allMatch("Bearer t0k"::equals);

            stub.session.set("expired"); // server forgets the session → 404 for the client's id
            try {
                client.listTools();
            } catch (RuntimeException firstCallMayFail) {
                // the SDK surfaces the lost session once, then re-initializes
            }
            assertThat(client.listTools().tools()).hasSize(1);
            assertThat(stub.initializes.get()).isEqualTo(2);
            assertThat(stub.authorization).allMatch("Bearer t0k"::equals);
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("SSE regression: Spring AI's SSE autoconfig drops headers — sseHeaderCustomizer restores Authorization")
    void sseHeaders() {
        var seen = new CopyOnWriteArrayList<String>();
        server = start("/sse", ex -> {
            seen.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            ex.close();
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var cache = cache(connection("legacy", url, McpConnection.SSE));
        var headers = new McpClientConfig.PerConnectionHeaderCustomizer(cache, ENV);

        // without the customizer (what Spring AI's autoconfig builds) the header is missing
        HttpClientSseClientTransport.builder(url).build().connect(m -> m).block(Duration.ofSeconds(5));
        var builder = HttpClientSseClientTransport.builder(url);
        new McpClientConfig().sseHeaderCustomizer(headers).customize("legacy", builder);
        builder.build().connect(m -> m).block(Duration.ofSeconds(5));

        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() >= 2);
        assertThat(seen.get(0)).isEqualTo("null");
        assertThat(seen.get(1)).isEqualTo("Bearer t0k");
    }

    private static HttpServer start(String path, com.sun.net.httpserver.HttpHandler handler) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            s.createContext(path, handler);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

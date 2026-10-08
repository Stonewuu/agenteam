package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolDefinitionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.OutboundAddressPolicy;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 以真实 HTTP 和事件流验证两种传输、分页、请求关联与完整参数约束。
 */
class RemoteMcpSessionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void streamableHttpDiscoversPagesWithoutExecutingToolsOrDroppingSchemaRules() throws Exception {
        try (var remote = new Server("json"); var session = connect(remote, 4 * 1024 * 1024)) {
            var raw = session.listTools();
            assertEquals(2, raw.size());
            assertEquals("string", raw.getFirst().path("inputSchema").path("additionalProperties").path("type").asText());
            var mapper = new PluginToolDefinitionMapper(new ResourceJson(json), new ToolSchemaValidation());
            var tools = mapper.remote(raw, 30);
            assertTrue(tools.stream().allMatch(tool -> tool.operationClass().equals("unknown") && !tool.supportsDeduplication()));
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/list"), remote.methods);
            assertTrue(remote.sessions.stream().allMatch(value -> value.equals("test-session")));
            assertTrue(remote.clientCapabilities.get().isEmpty());
        }
    }

    @Test
    void eventStreamResumesWithLastEventIdAndNeverRepeatsOriginalPost() throws Exception {
        try (var remote = new Server("resume"); var session = connect(remote, 4 * 1024 * 1024)) {
            assertEquals(2, session.listTools().size());
            assertEquals(1, remote.resumeGets.get());
            assertEquals("resume-1", remote.lastEventId.get());
            assertEquals(2, remote.methods.stream().filter(method -> method.equals("tools/list")).count());
        }
    }

    @Test
    void interruptedResponseContinuesReadingWithoutRepeatingTheRequest() throws Exception {
        try (var remote = new Server("interrupted"); var session = connect(remote, 4 * 1024 * 1024)) {
            assertEquals(2, session.listTools().size());
            assertEquals(1, remote.resumeGets.get());
            assertEquals("resume-1", remote.lastEventId.get());
            assertEquals(2, remote.methods.stream().filter(method -> method.equals("tools/list")).count());
        }
    }

    @Test
    void legacySseUsesServerEndpointAndReceivesResultsFromTheOriginalStream() throws Exception {
        try (var remote = new Server("legacy"); var session = connect(remote, 4 * 1024 * 1024)) {
            assertEquals(2, session.listTools().size());
            assertEquals(4, remote.legacyPosts.get());
            assertFalse(remote.methods.contains("tools/call"));
        }
    }

    @Test
    void negotiatesOnlyVersionsSupportedByTheSelectedTransport() throws Exception {
        for (String version : List.of("2025-03-26", "2025-06-18", "2025-11-25")) {
            try (var remote = new Server("json")) {
                remote.protocol = version;
                try (var session = connect(remote, 4 * 1024 * 1024)) {
                    assertEquals(2, session.listTools().size());
                }
            }
        }
        try (var remote = new Server("json")) {
            remote.protocol = "2024-11-05";
            assertEquals("MCP_CAPABILITY_UNSUPPORTED", assertThrows(ApiException.class, () -> connect(remote, 4 * 1024 * 1024)).code());
        }
    }

    @Test
    void totalDiscoveryBytesAndRepeatedPaginationCursorAreRejected() throws Exception {
        try (var remote = new Server("json"); var session = connect(remote, 600)) {
            assertEquals("REMOTE_RESPONSE_TOO_LARGE", assertThrows(ApiException.class, session::listTools).code());
        }
        try (var remote = new Server("repeat"); var session = connect(remote, 4 * 1024 * 1024)) {
            assertEquals("MCP_TOOL_STRUCTURE_INVALID", assertThrows(ApiException.class, session::listTools).code());
        }
        try (var remote = new Server("duplicate_fields"); var session = connect(remote, 4 * 1024 * 1024)) {
            assertEquals("REMOTE_CONNECTION_FAILED", assertThrows(ApiException.class, session::listTools).code());
        }
    }

    @Test
    void fullSchemaConstraintsRejectInvalidArgumentsAndUnsafeReferences() throws Exception {
        var schemas = new ToolSchemaValidation();
        var schema = json.readTree("""
            {"type":"object","properties":{"text":{"type":"string"}},"required":["text"],
             "additionalProperties":{"type":"string"},"allOf":[{"properties":{"text":{"minLength":3}}}]}
            """);
        schemas.arguments(schema, json.readTree("{\"text\":\"完整内容\",\"extra\":\"合法\"}"));
        assertThrows(ApiException.class, () -> schemas.arguments(schema, json.readTree("{\"text\":\"短\"}")));
        assertThrows(ApiException.class, () -> schemas.arguments(schema, json.readTree("{\"text\":\"完整内容\",\"extra\":123}")));
        assertThrows(ApiException.class, () -> schemas.compile(json.readTree("{\"type\":\"object\",\"$ref\":\"https://127.0.0.1/private\"}")));
        assertThrows(ApiException.class, () -> schemas.compile(json.readTree("{\"type\":\"object\",\"$defs\":{\"cycle\":{\"$ref\":\"#/$defs/cycle\"}}}")));
        assertThrows(ApiException.class, () -> schemas.compile(json.readTree("{\"type\":\"unknown\"}")));
        var adversarial = json.readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"pattern\":\"^(a+)+$\"}}}");
        long began = System.nanoTime();
        assertThrows(ApiException.class, () -> schemas.arguments(adversarial, json.valueToTree(Map.of("text", "a".repeat(40000) + "!"))));
        assertTrue(Duration.ofNanos(System.nanoTime() - began).compareTo(Duration.ofSeconds(2)) < 0);
    }

    private RemoteMcpSession connect(Server remote, long bytes) {
        String origin = "http://127.0.0.1:" + remote.server.getAddress().getPort();
        return new RemoteMcpSession(new RestrictedHttpClient(new OutboundAddressPolicy(origin)), json,
            origin + (remote.mode.equals("legacy") ? "/sse" : "/mcp"), remote.mode.equals("legacy") ? "legacy_sse" : "streamable_http",
            Map.of(), Duration.ofSeconds(5), bytes);
    }

    private final class Server implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final String mode;
        private String protocol;
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> sessions = new CopyOnWriteArrayList<>();
        private final AtomicReference<JsonNode> clientCapabilities = new AtomicReference<>();
        private final AtomicReference<String> lastEventId = new AtomicReference<>();
        private final AtomicInteger resumeGets = new AtomicInteger(), legacyPosts = new AtomicInteger();
        private final AtomicReference<JsonNode> resumeResult = new AtomicReference<>();
        private final CountDownLatch streamReady = new CountDownLatch(1), done = new CountDownLatch(1);
        private volatile OutputStream legacyStream;

        private Server(String mode) throws IOException {
            this.mode = mode;
            this.protocol = mode.equals("legacy") ? "2024-11-05" : "2025-11-25";
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/mcp", this::message);
            server.createContext("/messages", this::message);
            server.createContext("/sse", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                legacyStream = exchange.getResponseBody();
                legacyStream.write("event: endpoint\ndata: /messages\n\n".getBytes(StandardCharsets.UTF_8));
                legacyStream.flush();
                streamReady.countDown();
                try {
                    done.await(7, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        private void message(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("GET")) {
                resumeGets.incrementAndGet();
                lastEventId.set(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                event(exchange, "id: resume-2\ndata: " + resumeResult.get() + "\n\n");
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/messages")) {
                legacyPosts.incrementAndGet();
            }
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            if (!mode.equals("legacy") && !method.equals("initialize")) {
                sessions.add(exchange.getRequestHeaders().getFirst("MCP-Session-Id"));
            }
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            JsonNode result;
            if (method.equals("initialize")) {
                clientCapabilities.set(request.path("params").path("capabilities"));
                result = json.valueToTree(Map.of("protocolVersion", protocol, "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "验收服务", "version", "1")));
            } else {
                boolean next = request.path("params").has("cursor");
                var tool = json.readTree("""
                    {"name":"read_document","description":"读取文档","annotations":{"readOnlyHint":true,"idempotentHint":true},
                    "inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"additionalProperties":{"type":"string"},
                    "allOf":[{"properties":{"text":{"minLength":3}}}]}}
                    """);
                if (next) {
                    tool = json.valueToTree(Map.of("name", "find_document", "inputSchema", Map.of("type", "object")));
                }
                result = json.valueToTree(next && !mode.equals("repeat") ? Map.of("tools", List.of(tool)) : Map.of("tools", List.of(tool), "nextCursor", "page-2"));
            }
            var envelope = json.createObjectNode().put("jsonrpc", "2.0");
            envelope.set("id", request.get("id"));
            envelope.set("result", result);
            if (mode.equals("legacy")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                try {
                    assertTrue(streamReady.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                synchronized (this) {
                    legacyStream.write(("event: message\ndata: " + envelope + "\n\n").getBytes(StandardCharsets.UTF_8));
                    legacyStream.flush();
                }
            } else if ((mode.equals("resume") || mode.equals("interrupted")) && method.equals("tools/list") && !request.path("params").has("cursor")) {
                resumeResult.set(envelope);
                event(exchange, "id: resume-1\nretry: 100\ndata:\n\n");
            } else {
                byte[] bytes = json.writeValueAsBytes(envelope);
                if (mode.equals("duplicate_fields") && method.equals("tools/list")) {
                    bytes = ("{\"result\":{}," + envelope.toString().substring(1)).getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (method.equals("initialize")) {
                    exchange.getResponseHeaders().add("MCP-Session-Id", "test-session");
                }
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        }

        private void event(HttpExchange exchange, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            // 声明更长的正文后提前关闭，使客户端实际遇到响应未读完的网络异常。
            long length = mode.equals("interrupted") && text.startsWith("id: resume-1") ? bytes.length + 100 : bytes.length;
            exchange.sendResponseHeaders(200, length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().flush();
            exchange.close();
        }

        @Override
        public void close() {
            done.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

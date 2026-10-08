package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.plugin.entity.ToolQueryResult;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.network.RestrictedHttpClient;
import com.stonewu.agenteam.service.plugin.BuiltinPluginAdapter;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只用于测试的外部写入服务，实际按操作编号去重，并提供可控制的只读查询。
 */
public final class QueryableToolTestServer implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public final AtomicInteger writes = new AtomicInteger(), queries = new AtomicInteger();
    public final List<String> operationIds = new CopyOnWriteArrayList<>(), queryIds = new CopyOnWriteArrayList<>();
    public final List<JsonNode> received = new CopyOnWriteArrayList<>();
    public final Map<String, JsonNode> saved = new ConcurrentHashMap<>();
    public volatile String mode = "lost";
    public volatile Runnable beforeQuery = () -> {
    };
    public volatile boolean holdFirstQuery;
    public CountDownLatch queryStarted = new CountDownLatch(1), releaseQuery = new CountDownLatch(1);

    public QueryableToolTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/write", this::write);
            server.createContext("/operations/", this::query);
            server.start();
        } catch (IOException failed) {
            throw new IllegalStateException("无法启动可查询工具验收服务", failed);
        }
    }

    public String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset(String nextMode) {
        releaseQuery.countDown();
        mode = nextMode;
        writes.set(0);
        queries.set(0);
        operationIds.clear();
        queryIds.clear();
        received.clear();
        saved.clear();
        beforeQuery = () -> {
        };
        holdFirstQuery = false;
        queryStarted = new CountDownLatch(1);
        releaseQuery = new CountDownLatch(1);
    }

    private void write(HttpExchange exchange) throws IOException {
        try {
            var input = json.readTree(exchange.getRequestBody());
            String operation = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            int attempt = writes.incrementAndGet();
            operationIds.add(operation);
            received.add(input);
            if (mode.equals("never") || (mode.equals("once") && attempt == 1)) {
                return;
            }
            var result = json.valueToTree(Map.of("operationId", operation, "text", input.path("text").asText()));
            saved.putIfAbsent(operation, result);
            if (mode.equals("once")) {
                respond(exchange, saved.get(operation));
            } else if (mode.equals("timeout")) {
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            exchange.close();
        }
    }

    private void query(HttpExchange exchange) throws IOException {
        try {
            String operation = exchange.getRequestURI().getPath().substring("/operations/".length());
            queryIds.add(operation);
            int number = queries.incrementAndGet();
            queryStarted.countDown();
            if (holdFirstQuery && number == 1) {
                try {
                    if (!releaseQuery.await(15, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (mode.equals("unknown")) {
                respond(exchange, json.valueToTree(Map.of("status", "UNKNOWN")));
            } else if (!saved.containsKey(operation)) {
                respond(exchange, json.valueToTree(Map.of("status", "NOT_EXECUTED")));
            } else {
                respond(exchange, json.valueToTree(Map.of("status", "COMPLETED", "result", saved.get(operation))));
            }
        } finally {
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, JsonNode response) throws IOException {
        byte[] bytes = json.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public BuiltinPluginAdapter adapter(RestrictedHttpClient http, ResourceJson values) {
        var input = values.tree(Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text"), "additionalProperties", false));
        var output = values.tree(Map.of("type", "object"));
        var hash = values.hash(values.tree(Map.of("name", "write_record", "input", input, "output", output, "operationClass", "write", "query", true, "deduplication", true)));
        var definition = new ToolDefinition("write_record", "写入并按原编号查询测试记录", hash, input, output, values.tree(Map.of()), "write", true, true, false, List.of(), 10);
        return new BuiltinPluginAdapter() {
            @Override
            public String code() {
                return "query_test";
            }

            @Override
            public String name() {
                return "可查询验收工具";
            }

            @Override
            public String description() {
                return "仅用于隔离测试的真实请求服务";
            }

            @Override
            public List<ToolDefinition> tools() {
                return List.of(definition);
            }

            @Override
            public JsonNode call(String tool, String operation, JsonNode arguments, Duration timeout, ToolCallControl control) {
                return request("POST", origin() + "/write", operation, arguments, timeout, control);
            }

            @Override
            public ToolQueryResult query(String tool, String operation, Duration timeout, ToolCallControl control) {
                beforeQuery.run();
                var response = request("GET", origin() + "/operations/" + operation, operation, null, timeout, control);
                return new ToolQueryResult(ToolQueryResult.Status.valueOf(response.path("status").asText()), response.get("result"));
            }

            private JsonNode request(String method, String url, String operation, JsonNode body, Duration timeout, ToolCallControl control) {
                try (var scope = control.track(http.open(url, Map.of(), timeout));
                     var response = scope.execute(method, url, body == null ? null : json.writeValueAsBytes(body),
                         Map.of("Content-Type", "application/json", "Idempotency-Key", operation), control::beforeSend)) {
                    return json.readTree(response.response().body().byteStream());
                } catch (IOException failed) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_CONNECTION_FAILED", "验收目标没有返回完整响应。");
                }
            }
        };
    }

    @Override
    public void close() {
        releaseQuery.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}

package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 提供可控制的真实远程工具，测试可以暂停检查或改变参数结构。
 */
public final class PluginTestServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json = new ObjectMapper();
    public final List<String> methods = new CopyOnWriteArrayList<>();
    public final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    public volatile JsonNode tools;
    public volatile Runnable beforeList = () -> {
    };
    public volatile int status = 200;
    public volatile Function<JsonNode, JsonNode> toolResult = arguments -> json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "操作完成")), "isError", false));

    public PluginTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/mcp", this::handle);
            server.start();
            reset();
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动测试插件服务", failure);
        }
    }

    public String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String endpoint() {
        return origin() + "/mcp";
    }

    public void textPage(String path, String text) {
        server.createContext(path, exchange -> {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    public void reset() {
        methods.clear();
        authorizationHeaders.clear();
        status = 200;
        beforeList = () -> {
        };
        tools = json.valueToTree(List.of(tool("Read_Item", 1), tool("read_item", 1)));
    }

    public Map<String, Object> tool(String name, int minimum) {
        return Map.of("name", name, "description", "读取测试资料", "inputSchema", Map.of("type", "object",
                "properties", Map.of("text", Map.of("type", "string", "minLength", minimum)), "required", List.of("text")),
            "annotations", Map.of("readOnlyHint", true));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            authorizationHeaders.add(authorization);
        }
        if (status != 200) {
            byte[] body = json.writeValueAsBytes(Map.of("message", "远程认证失败，原始内容不应对外展示"));
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        JsonNode request = json.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        methods.add(method);
        if (!request.has("id")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        JsonNode result;
        switch (method) {
            case "initialize" ->
                result = json.valueToTree(Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "插件测试服务", "version", "1")));
            case "tools/list" -> {
                beforeList.run();
                result = json.createObjectNode().set("tools", tools);
            }
            case "tools/call" -> result = toolResult.apply(request.path("params").path("arguments"));
            default -> throw new IOException("测试服务没有实现此方法");
        }
        var envelope = json.createObjectNode().put("jsonrpc", "2.0");
        envelope.set("id", request.get("id"));
        envelope.set("result", result);
        byte[] bytes = json.writeValueAsBytes(envelope);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

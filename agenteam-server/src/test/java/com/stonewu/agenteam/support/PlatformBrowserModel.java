package com.stonewu.agenteam.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 界面验收专用的本机模型，提供流式、暂停、双子任务和单侧失败场景。
 */
public final class PlatformBrowserModel implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json = new ObjectMapper();
    private final Path requests;
    private final String toolsOrigin;
    private final Map<String, AtomicInteger> scheduleFailures = new ConcurrentHashMap<>();

    public PlatformBrowserModel(Path requests, String toolsOrigin) throws IOException {
        this.requests = requests;
        this.toolsOrigin = toolsOrigin;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::respond);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            var input = json.readTree(exchange.getRequestBody());
            synchronized (this) {
                Files.writeString(requests, json.writeValueAsString(input) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            String question = "";
            boolean toolResult = false;
            JsonNode lastToolResult = null;
            for (var message : input.path("messages")) {
                if (message.path("role").asText().equals("user")) {
                    question = text(message.path("content"));
                    toolResult = false;
                    lastToolResult = null;
                }
                if (message.path("role").asText().equals("tool")) {
                    toolResult = true;
                    try {
                        lastToolResult = json.readTree(text(message.path("content")));
                    } catch (IOException ignored) {
                        lastToolResult = null;
                    }
                }
            }
            String model = input.path("model").asText();
            boolean temporaryScheduleFailure = question.contains("计划重试验收") && !toolResult && scheduleFailures.computeIfAbsent(question, ignored -> new AtomicInteger()).getAndIncrement() < 2;
            if ((question.contains("右侧失败") && model.equals("ui-right")) || temporaryScheduleFailure) {
                byte[] body = "{\"error\":{\"message\":\"本机验收模型模拟服务不可用\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String selectedTool = null;
            boolean writing = question.contains("记录"), reading = question.contains("网页") || question.contains("大结果");
            for (var tool : input.path("tools")) {
                String description = tool.path("function").path("description").asText();
                if ((writing && description.contains("写入验收记录")) || (!writing && reading && description.contains("读取指定网页"))) {
                    selectedTool = tool.path("function").path("name").asText();
                }
            }
            var source = sourceCall(input, question, toolResult, lastToolResult);
            if (source != null) {
                frame(exchange, source, "tool_calls");
            } else if (selectedTool != null && !toolResult) {
                var arguments = writing ? Map.of("text", question.split("\\n\\n本次任务选择的技能", 2)[0]) : Map.of("url", toolsOrigin + (question.contains("大结果") ? "/large" : "/page"));
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "browser-plugin-operation", "type", "function", "function", Map.of("name", selectedTool, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
            } else if (question.contains("并行") && !toolResult) {
                frame(exchange, Map.of("content", "我会分别整理两项资料。\n\n"), null);
                frame(exchange, Map.of("tool_calls", List.of(spawn(0, "浏览器甲", "浏览器子任务甲"), spawn(1, "浏览器乙", "浏览器子任务乙"))), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "这是" + (model.equals("ui-right") ? "模型乙" : "模型甲") + "的回答。\n\n"), null);
                if (question.contains("暂停")) {
                    TimeUnit.SECONDS.sleep(20);
                }
                String visibleQuestion = question.split("\\n\\n本次任务选择的技能", 2)[0];
                for (String part : List.of("已读取问题：", visibleQuestion, "\n\n", "- 已整理已有资料。\n", "- 可以继续补充需要分析的内容。\n")) {
                    frame(exchange, Map.of("content", part), null);
                    TimeUnit.MILLISECONDS.sleep(180);
                }
                if (input.toString().contains("临时草稿标记")) {
                    frame(exchange, Map.of("content", "\n已使用本次编辑指令。"), null);
                }
                frame(exchange, Map.of("content", ""), "stop");
            }
            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> spawn(int index, String label, String task) throws IOException {
        return Map.of("index", index, "id", "browser-call-" + index, "type", "function", "function", Map.of("name", "agent_spawn",
            "arguments", json.writeValueAsString(Map.of("agent_id", "researcher", "label", label, "task", task))));
    }

    private Map<String, Object> sourceCall(JsonNode input, String question, boolean hasResult, JsonNode result) throws IOException {
        String kind = null;
        Map<String, Object> arguments = Map.of();
        if (question.contains("检索资料") && !hasResult) {
            kind = "knowledge_search";
            String query = question.contains("：") ? question.substring(question.indexOf('：') + 1).split("\\n", 2)[0].trim() : "休假申请";
            arguments = Map.of("query", query);
        } else if (question.contains("查询数据")) {
            if (!hasResult) {
                kind = "data_collections";
            } else if (result != null && result.path("items").isArray() && !result.path("items").isEmpty()) {
                var collection = result.path("items").get(0);
                var fields = new ArrayList<String>();
                for (var field : collection.path("fields")) {
                    if (field.path("readable").asBoolean()) {
                        fields.add(field.path("name").asText());
                    }
                }
                if (!fields.isEmpty()) {
                    kind = "data_query";
                    arguments = Map.of("collectionId", collection.path("id").asText(), "generation", collection.path("activeGeneration").asInt(), "fields", fields, "filters", List.of(), "sort", List.of());
                }
            }
        }
        if (kind == null) {
            return null;
        }
        for (var tool : input.path("tools")) {
            if (tool.at("/function/name").asText().startsWith("platform_" + kind + "_")) {
                return Map.of("tool_calls", List.of(Map.of("index", 0, "id", "browser-" + kind, "type", "function", "function",
                    Map.of("name", tool.at("/function/name").asText(), "arguments", json.writeValueAsString(arguments)))));
            }
        }
        return null;
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        var choice = json.createObjectNode().put("index", 0);
        choice.set("delta", json.valueToTree(delta));
        if (finish != null) {
            choice.put("finish_reason", finish);
        }
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(Map.of("id", "browser-response", "choices", List.of(choice))) + "\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private String text(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        var text = new StringBuilder();
        content.forEach(part -> text.append(part.path("text").asText()));
        return text.toString();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

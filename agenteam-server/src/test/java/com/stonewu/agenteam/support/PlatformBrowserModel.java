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
        this(requests, toolsOrigin, 0);
    }

    public PlatformBrowserModel(Path requests, String toolsOrigin, int port) throws IOException {
        this.requests = requests;
        this.toolsOrigin = toolsOrigin;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
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
            if (!input.path("tools").isEmpty() && reconnectScenario(exchange, input, question, toolResult)) {
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                return;
            }
            if (input.path("messages").toString().contains("为用户提供的资料拟一个")) {
                TimeUnit.SECONDS.sleep(6);
                frame(exchange, Map.of("content", "团队活动筹备"), "stop");
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                return;
            }
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
            } else if (selectedTool != null && !toolResult && question.contains("工具分组验收")) {
                frame(exchange, Map.of("content", "开始整理三份资料。\n\n"), null);
                frame(exchange, Map.of("reasoning_content", "先核对资料，再逐项记录。"), null);
                var calls = new ArrayList<Map<String, Object>>();
                for (int index = 0; index < 3; index++) {
                    calls.add(Map.of("index", index, "id", "browser-group-" + index, "type", "function",
                        "function", Map.of("name", selectedTool, "arguments", json.writeValueAsString(
                            Map.of("text", "工具分组验收第 " + (index + 1) + " 条")))));
                }
                frame(exchange, Map.of("tool_calls", calls), "tool_calls");
            } else if (selectedTool != null && !toolResult) {
                var arguments = writing ? Map.of("text", question.split("\\n\\n本次任务选择的技能", 2)[0]) : Map.of("url", toolsOrigin + (question.contains("大结果") ? "/large" : "/page"));
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "browser-plugin-operation", "type", "function", "function", Map.of("name", selectedTool, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
            } else if (question.contains("并行") && !toolResult) {
                frame(exchange, Map.of("content", "我会分别整理两项资料。\n\n"), null);
                frame(exchange, Map.of("tool_calls", List.of(spawn(input, 0, "浏览器甲", "浏览器子任务甲"),
                    spawn(input, 1, "浏览器乙", "浏览器子任务乙"))), "tool_calls");
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

    private Map<String, Object> spawn(JsonNode input, int index, String label, String task) throws IOException {
        String agentId = null;
        for (var tool : input.path("tools")) {
            if (tool.at("/function/name").asText().equals("agent_spawn")) {
                agentId = tool.at("/function/parameters/properties/agent_id/enum/0").asText();
            }
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException("Browser fixture requires a published subagent");
        }
        return Map.of("index", index, "id", "browser-call-" + index, "type", "function", "function", Map.of("name", "agent_spawn",
            "arguments", json.writeValueAsString(Map.of("agent_id", agentId, "label", label, "task", task))));
    }

    /** 固定编号用于逐项核对重开页面和停止后的内容，不依赖模型生成的措辞。 */
    private boolean reconnectScenario(HttpExchange exchange, JsonNode input, String question, boolean toolResult)
        throws IOException, InterruptedException {
        if (!question.startsWith("重连验收")) {
            return false;
        }
        if (question.contains("并行") && !toolResult) {
            frame(exchange, Map.of("content", "父任务准备：按甲、乙、丙顺序启动三个子任务。\n\n"), null);
            var calls = new ArrayList<Map<String, Object>>();
            for (int index = 0; index < 3; index++) {
                String label = List.of("甲", "乙", "丙").get(index);
                calls.add(spawn(input, index, "核对" + label, "重连验收子任务" + label));
            }
            frame(exchange, Map.of("tool_calls", calls), "tool_calls");
        } else if (question.startsWith("重连验收子任务")) {
            String label = question.substring("重连验收子任务".length(), "重连验收子任务".length() + 1);
            // 故意让后启动的乙先完成，检查页面是否仍按开始顺序排列。
            long delay = label.equals("乙") ? 650 : label.equals("甲") ? 850 : 1050;
            reconnectText(exchange, "子任务" + label, 60, delay);
        } else if (toolResult) {
            reconnectText(exchange, "父任务汇总", 6, 300);
        } else {
            reconnectText(exchange, "普通回复", 60, 900);
        }
        return true;
    }

    private void reconnectText(HttpExchange exchange, String label, int count, long delay)
        throws IOException, InterruptedException {
        for (int index = 1; index <= count; index++) {
            frame(exchange, Map.of("content", label + "第" + String.format("%02d", index)
                + "段：这段内容应当只显示一次，并保持原有顺序。\n\n"), null);
            TimeUnit.MILLISECONDS.sleep(delay);
        }
        frame(exchange, Map.of("content", label + "已全部完成。"), "stop");
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

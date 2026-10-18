package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.TestOutputPaths;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实模型流与网页请求验证工具执行、文件权限和实际调用次数。
 */
@Import(SharedEnterpriseTestEdition.class)
class PluginToolExecutionApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final HttpServer WEB = webServer();

    private static final AtomicInteger READS = new AtomicInteger();

    private static volatile String content = "可读取的网页资料";

    @Autowired
    private PreviewRetentionService previewRetention;

    @Autowired
    private FileMapper files;

    @Autowired
    private RunMapper runs;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", PluginToolExecutionApiTest::origin);
        registry.add("execution.workspace-root", () -> TestOutputPaths.path("p04-plugin-execution-workspace").toString());
        registry.add("execution.state-root", () -> TestOutputPaths.path("p04-plugin-execution-state").toString());
        registry.add("files.root", () -> TestOutputPaths.path("p04-plugin-execution-files").toString());
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        WEB.stop(0);
        ENVIRONMENT.close();
    }

    @Test
    void parentAndChildReadsStayEncryptedAndPrivateWhileUnsafeAddressesAreBlocked() throws Exception {
        plugin();
        configureAgent(config -> config.put("dynamicSubagentEnabled", true));
        content = "资料正文。password=remote-password";
        int before = READS.get();
        var parentCalls = new AtomicInteger();
        var childCalls = new AtomicInteger();
        var parentResult = new AtomicReference<String>();
        var childResult = new AtomicReference<String>();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            String question = "";
            for (var message : request.path("messages")) {
                if (message.path("role").asText().equals("user")) {
                    var value = message.path("content");
                    if (value.isTextual()) {
                        question = value.asText();
                    } else {
                        var text = new StringBuilder();
                        value.forEach(part -> text.append(part.path("text").asText()));
                        question = text.toString();
                    }
                }
            }
            if (question.equals("父任务读取资料")) {
                if (parentCalls.getAndIncrement() == 0) {
                    String read = modelTool(request, "read_url");
                    frame(exchange, Map.of("tool_calls", List.of(
                        call(0, "read-" + "a".repeat(123), read, Map.of("url", origin() + "/page")),
                        call(1, "denied-address", read, Map.of("url", "http://169.254.169.254/latest/meta-data/")),
                        call(2, "spawn-child", "agent_spawn", Map.of("agent_id", "general-purpose", "label", "资料查阅", "task", "子任务读取资料")))), "tool_calls");
                } else {
                    parentResult.set(request.toString());
                    frame(exchange, Map.of("content", "父任务已整理可读取的资料"), "stop");
                }
            } else if (childCalls.getAndIncrement() == 0) {
                frame(exchange, Map.of("tool_calls", List.of(call(0, "child-read", modelTool(request, "read_url"),
                    Map.of("url", origin() + "/child")))), "tool_calls");
            } else {
                childResult.set(request.toString());
                frame(exchange, Map.of("content", "子任务已完成查阅"), "stop");
            }
        };
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("父任务读取资料")),
            UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("completed", state(run), () -> diagnostic(run));
        assertEquals(before + 2, READS.get());
        for (var result : List.of(parentResult.get(), childResult.get())) {
            assertTrue(result.contains("资料正文"));
            assertFalse(result.contains("remote-password"));
        }
        var stored = databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>()
            .eq(ToolCallRow::getEnterpriseId, enterprise).eq(ToolCallRow::getRunId, run));
        assertEquals(3, stored.size());
        var successful = stored.stream().filter(call -> call.getStatus().equals("succeeded")).toList();
        assertEquals(2, successful.size());
        for (var call : successful) {
            assertEquals("read", call.getOperationClass());
            assertEquals(1, call.getAttemptCount());
            assertNotNull(call.getSubmittedAt());
            assertFalse(call.getRequestEncryptedJson().contains(origin()));
            assertFalse(call.getResultEncryptedJson().contains("资料正文"));
        }
        var denied = stored.stream().filter(call -> "NETWORK_ADDRESS_DENIED".equals(call.getErrorCode()))
            .findFirst().orElseThrow();
        assertEquals("failed", denied.getStatus());
        assertEquals(0, denied.getAttemptCount());
        assertNull(denied.getSubmittedAt());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText())
            .cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertTrue(snapshot.toString().contains("网页读取"));
        assertFalse(snapshot.toString().contains("remote-password"));
        assertFalse(snapshot.toString().contains("platform_"));
        String child = "";
        for (var block : snapshot.at("/messages/1/blocks")) {
            if (block.path("type").asText().equals("subagent")) {
                child = block.path("id").asText();
            }
        }
        assertFalse(child.isEmpty());
        boolean found = false;
        for (var block : snapshot.at("/messages/1/blocks")) {
            if (block.path("type").asText().equals("tool") && block.path("parentBlockId").asText().equals(child)) {
                assertEquals("completed", block.path("status").asText());
                found = true;
            }
        }
        assertTrue(found, "子任务工具结果必须保存在该子任务下面");
        assertEquals(0, count("run_approval"));
    }

    @Test
    void largePreviewResultsSupportReadingAndRevocationAndExpireWithTheirExecution() throws Exception {
        String plugin = plugin();
        content = "资料".repeat(300000);
        var requests = new AtomicReference<String>();
        model(origin() + "/large", requests);
        allowModelCalls = true;
        var accepted = data(preview(Map.of("input", input("读取预览资料")), UUID.randomUUID().toString())
            .andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText(), conversation = accepted.path("conversationId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("completed", state(run), () -> diagnostic(run));
        assertTrue(files.forRun(enterprise, run).isEmpty(), "工具结果不应生成附件");
        var call = databaseAccess.mapper(ToolCallSqlMapper.class).selectOne(new LambdaQueryWrapper<ToolCallRow>()
            .eq(ToolCallRow::getRunId, run));
        var details = json.readTree(call.getResultRedactedJson());
        assertEquals(content, details.path("content").asText());
        assertFalse(details.has("fileId"));
        assertTrue(requests.get().contains("tool-results/" + call.getId() + "/content.txt"));
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertFalse(snapshot.toString().contains("\"type\":\"attachment\""));
        String url = base() + "/runs/" + run + "/steps/" + call.getStepId() + "/tool-content/download";
        var pending = mvc.perform(get(url).cookie(cookie)).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
        assertTrue(response.getResponse().getContentAsByteArray().length > 1024 * 1024);
        change(HttpMethod.PATCH, base() + "/resources/" + plugin + "/status", Map.of("status", "disabled"), "2",
            UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(get(url).cookie(cookie)).andExpect(status().isNotFound());
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getId, conversation)
            .set(AgentConversationRow::getCreatedAt, Timestamp.from(Instant.now().minusSeconds(8 * 86400))));
        previewRetention.clean();
        previewRetention.clean();
        assertEquals(0, count("tool_call"));
        assertEquals(0, count("run_checkpoint"));
        assertEquals(0, count("agent_run"));
        assertTrue(files.forRun(enterprise, run).isEmpty());
    }

    private Map<String, Object> call(int index, String id, String name, Map<String, Object> arguments) throws IOException {
        return Map.of("index", index, "id", id, "type", "function", "function",
            Map.of("name", name, "arguments", json.writeValueAsString(arguments)));
    }

    private String state(String run) {
        return runs.find(enterprise, run, false).orElseThrow().status();
    }

    private String diagnostic(String run) {
        var value = runs.find(enterprise, run, false).orElseThrow();
        return "状态：" + value.status() + "，错误码：" + value.errorCode() + "，原因：" + value.errorMessage();
    }

    private boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(state(run));
    }

    private void model(String url, AtomicReference<String> followup) {
        var iteration = new AtomicInteger();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                String name = modelTool(request, "read_url");
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "read-" + "a".repeat(123), "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(Map.of("url", url)))))), "tool_calls");
            } else {
                followup.set(request.toString());
                frame(exchange, Map.of("content", "已经读取资料。"), "stop");
            }
        };
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", "plugin-model", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private String plugin() throws Exception {
        var config = (ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"builtin","builtinCode":"web_read","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """);
        String id = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "网页读取", "description", "读取测试地址", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String version = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "发布网页工具"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(agentConfig -> agentConfig.set("pluginVersionIds", json.valueToTree(List.of(version))));
        return id;
    }

    private static String origin() {
        return "http://127.0.0.1:" + WEB.getAddress().getPort();
    }

    private static HttpServer webServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                READS.incrementAndGet();
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("无法启动工具验收服务", error);
        }
    }
}

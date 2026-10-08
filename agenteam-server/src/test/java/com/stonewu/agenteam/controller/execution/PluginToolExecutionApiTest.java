package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.FileSqlMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallSqlMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", PluginToolExecutionApiTest::origin);
        registry.add("execution.workspace-root", () -> "target/p04-plugin-execution-workspace");
        registry.add("execution.state-root", () -> "target/p04-plugin-execution-state");
        registry.add("files.root", () -> "target/p04-plugin-execution-files");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        WEB.stop(0);
        ENVIRONMENT.close();
    }

    @Test
    void publishedReadToolRunsOnceWithEncryptedInputAndSanitizedPublicResult() throws Exception {
        String plugin = plugin();
        content = "资料正文。password=remote-password";
        int before = READS.get();
        var requests = new AtomicReference<String>();
        model(origin() + "/page", requests);
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run));
            assertEquals(before + 1, READS.get());
            assertTrue(requests.get().contains("资料正文"));
            assertFalse(requests.get().contains("remote-password"));
            var call = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectMaps(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getId, ToolCallRow::getEnterpriseId, ToolCallRow::getRunId, ToolCallRow::getAttemptId, ToolCallRow::getStepId, ToolCallRow::getActorUserId, ToolCallRow::getResourceId, ToolCallRow::getResourceKind, ToolCallRow::getResourceVersionId, ToolCallRow::getDraftRevision, ToolCallRow::getToolName, ToolCallRow::getOperationId, ToolCallRow::getPluginToolId, ToolCallRow::getFrameworkCallId, ToolCallRow::getFrameworkSessionId, ToolCallRow::getArgumentHash, ToolCallRow::getRequestEncryptedJson, ToolCallRow::getResultEncryptedJson, ToolCallRow::getLeaseVersion, ToolCallRow::getSubmittedAt, ToolCallRow::getOperationClass, ToolCallRow::getStatus, ToolCallRow::getRequestHash, ToolCallRow::getRequestRedactedJson, ToolCallRow::getResultRedactedJson, ToolCallRow::getAttemptCount, ToolCallRow::getQueryCount, ToolCallRow::getLastQueryAt, ToolCallRow::getErrorCode, ToolCallRow::getErrorSummary, ToolCallRow::getStartedAt, ToolCallRow::getFinishedAt, ToolCallRow::getDurationMs, ToolCallRow::getCreatedAt, ToolCallRow::getUpdatedAt).eq(ToolCallRow::getEnterpriseId, (enterprise)).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureValues -> {
                Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
                fixtureRow.put("id", fixtureValues.get("id"));
                fixtureRow.put("enterprise_id", fixtureValues.get("enterprise_id"));
                fixtureRow.put("run_id", fixtureValues.get("run_id"));
                fixtureRow.put("attempt_id", fixtureValues.get("attempt_id"));
                fixtureRow.put("step_id", fixtureValues.get("step_id"));
                fixtureRow.put("actor_user_id", fixtureValues.get("actor_user_id"));
                fixtureRow.put("resource_id", fixtureValues.get("resource_id"));
                fixtureRow.put("resource_kind", fixtureValues.get("resource_kind"));
                fixtureRow.put("resource_version_id", fixtureValues.get("resource_version_id"));
                fixtureRow.put("draft_revision", fixtureValues.get("draft_revision"));
                fixtureRow.put("tool_name", fixtureValues.get("tool_name"));
                fixtureRow.put("operation_id", fixtureValues.get("operation_id"));
                fixtureRow.put("plugin_tool_id", fixtureValues.get("plugin_tool_id"));
                fixtureRow.put("framework_call_id", fixtureValues.get("framework_call_id"));
                fixtureRow.put("framework_session_id", fixtureValues.get("framework_session_id"));
                fixtureRow.put("argument_hash", fixtureValues.get("argument_hash"));
                fixtureRow.put("request_encrypted_json", fixtureValues.get("request_encrypted_json"));
                fixtureRow.put("result_encrypted_json", fixtureValues.get("result_encrypted_json"));
                fixtureRow.put("lease_version", fixtureValues.get("lease_version"));
                fixtureRow.put("submitted_at", fixtureValues.get("submitted_at"));
                fixtureRow.put("operation_class", fixtureValues.get("operation_class"));
                fixtureRow.put("status", fixtureValues.get("status"));
                fixtureRow.put("request_hash", fixtureValues.get("request_hash"));
                fixtureRow.put("request_redacted_json", fixtureValues.get("request_redacted_json"));
                fixtureRow.put("result_redacted_json", fixtureValues.get("result_redacted_json"));
                fixtureRow.put("attempt_count", fixtureValues.get("attempt_count"));
                fixtureRow.put("query_count", fixtureValues.get("query_count"));
                fixtureRow.put("last_query_at", fixtureValues.get("last_query_at"));
                fixtureRow.put("error_code", fixtureValues.get("error_code"));
                fixtureRow.put("error_summary", fixtureValues.get("error_summary"));
                fixtureRow.put("started_at", fixtureValues.get("started_at"));
                fixtureRow.put("finished_at", fixtureValues.get("finished_at"));
                fixtureRow.put("duration_ms", fixtureValues.get("duration_ms"));
                fixtureRow.put("created_at", fixtureValues.get("created_at"));
                fixtureRow.put("updated_at", fixtureValues.get("updated_at"));
                return fixtureRow;
            }).toList());
            assertEquals("succeeded", call.get("status"));
            assertEquals("read", call.get("operation_class"));
            assertEquals(1, ((Number) call.get("attempt_count")).intValue());
            assertNotNull(call.get("submitted_at"));
            assertFalse(call.get("request_encrypted_json").toString().contains(origin()));
            assertFalse(call.get("result_encrypted_json").toString().contains("资料正文"));
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertTrue(snapshot.toString().contains("网页读取"));
            assertFalse(snapshot.toString().contains("remote-password"));
            assertFalse(snapshot.toString().contains("platform_"));
            assertEquals(0, count("run_approval"));
            assertEquals(1, count("tool_call"));
        }
    }

    @Test
    void dynamicChildUsesTheFixedReadToolAndKeepsItsOwnMessageParent() throws Exception {
        plugin();
        configureAgent(config -> config.put("dynamicSubagentEnabled", true));
        content = "子任务读取到的真实资料";
        int before = READS.get();
        var parentCalls = new AtomicInteger();
        var childCalls = new AtomicInteger();
        var childFollowup = new AtomicReference<String>();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            String question = "";
            for (var message : request.path("messages")) {
                if (message.path("role").asText().equals("user")) {
                    var text = message.path("content");
                    if (text.isTextual()) {
                        question = text.asText();
                    } else {
                        var parts = new StringBuilder();
                        text.forEach(part -> parts.append(part.path("text").asText()));
                        question = parts.toString();
                    }
                }
            }
            String name;
            Map<String, Object> arguments;
            if (question.equals("父任务读取资料")) {
                if (parentCalls.getAndIncrement() > 0) {
                    frame(exchange, Map.of("content", "父任务已整理子任务资料"), "stop");
                    return;
                }
                name = "agent_spawn";
                arguments = Map.of("agent_id", "general-purpose", "label", "资料查阅", "task", "子任务读取资料");
            } else {
                if (childCalls.getAndIncrement() > 0) {
                    childFollowup.set(request.toString());
                    frame(exchange, Map.of("content", "子任务已完成查阅"), "stop");
                    return;
                }
                name = modelTool(request, "read_url");
                arguments = Map.of("url", origin() + "/child");
            }
            frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "call-" + name, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
        };
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("父任务读取资料")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        var lease = lifecycle.claim("child-read-test").orElseThrow();
        var started = lifecycle.start(lease).orElseThrow();
        try (var task = adapter.create(started, lease)) {
            task.completion().block();
            task.saveCheckpoint();
            lifecycle.finish(lease, "completed", null, null);
            assertEquals("completed", state(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
                Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
                fixtureRow.put("error_code", fixtureValues.get("error_code"));
                fixtureRow.put("error_message", fixtureValues.get("error_message"));
                return fixtureRow;
            }).toList()).toString());
            assertEquals(before + 1, READS.get());
            assertTrue(childFollowup.get().contains(content));
            assertEquals(0, count("run_approval"));
            assertEquals(1, count("tool_call"));
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            schemas.validate("ConversationSnapshot", snapshot);
            String childBlock = "";
            for (var block : snapshot.at("/messages/1/blocks")) {
                if (block.path("type").asText().equals("subagent")) {
                    childBlock = block.path("id").asText();
                }
            }
            assertFalse(childBlock.isEmpty());
            boolean readToolFound = false;
            for (var block : snapshot.at("/messages/1/blocks")) {
                if (block.path("type").asText().equals("tool") && block.path("parentBlockId").asText().equals(childBlock)) {
                    assertEquals("completed", block.path("status").asText());
                    readToolFound = true;
                }
            }
            assertTrue(readToolFound, "子任务的工具结果必须保存在该子任务下面");
        }
    }

    @Test
    void oversizedResultCreatesRestrictedAttachmentAndDisablingSourceBlocksOldDownload() throws Exception {
        String plugin = plugin();
        content = "资料".repeat(300000);
        var requests = new AtomicReference<String>();
        model(origin() + "/large", requests);
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus, AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
                Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
                fixtureRow.put("status", fixtureValues.get("status"));
                fixtureRow.put("error_code", fixtureValues.get("error_code"));
                fixtureRow.put("error_message", fixtureValues.get("error_message"));
                return fixtureRow;
            }).toList()).toString());
            String file = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(FileSqlMapper.class).selectList(new LambdaQueryWrapper<FileObjectRow>().select(FileObjectRow::getId).eq(FileObjectRow::getEnterpriseId, (enterprise)).eq(FileObjectRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getId()).toList());
            var details = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectList(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getResultRedactedJson).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureRecord -> fixtureRecord.getResultRedactedJson()).toList()));
            assertTrue(details.path("truncated").asBoolean());
            assertEquals(file, details.path("fileId").asText());
            assertTrue(requests.get().contains("tool-results/" + file + "/content.txt"), "模型必须收到可继续读取的真实文件路径");
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertTrue(snapshot.toString().contains("\"type\":\"attachment\""));
            schemas.validate("ConversationSnapshot", snapshot);
            var download = data(mvc.perform(get(base() + "/files/" + file + "/download").cookie(cookie)).andExpect(status().isOk()).andReturn());
            String url = download.path("url").asText();
            var pending = mvc.perform(get(url).cookie(cookie)).andReturn();
            var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn();
            assertTrue(response.getResponse().getContentAsByteArray().length > 1024 * 1024);
            change(HttpMethod.PATCH, base() + "/resources/" + plugin + "/status", Map.of("status", "disabled"), "2", UUID.randomUUID().toString()).andExpect(status().isOk());
            mvc.perform(get(url).cookie(cookie)).andExpect(status().isNotFound());
        }
    }

    @Test
    void metadataAddressIsRejectedBeforeAnyHttpRequest() throws Exception {
        plugin();
        int before = READS.get();
        model("http://169.254.169.254/latest/meta-data/", new AtomicReference<>());
        var accepted = submit();
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertEquals(before, READS.get());
            var call = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ToolCallSqlMapper.class).selectMaps(new LambdaQueryWrapper<ToolCallRow>().select(ToolCallRow::getStatus, ToolCallRow::getErrorCode, ToolCallRow::getAttemptCount, ToolCallRow::getSubmittedAt).eq(ToolCallRow::getRunId, (run))).stream().map(fixtureValues -> {
                Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
                fixtureRow.put("status", fixtureValues.get("status"));
                fixtureRow.put("error_code", fixtureValues.get("error_code"));
                fixtureRow.put("attempt_count", fixtureValues.get("attempt_count"));
                fixtureRow.put("submitted_at", fixtureValues.get("submitted_at"));
                return fixtureRow;
            }).toList());
            assertEquals("failed", call.get("status"));
            assertEquals("NETWORK_ADDRESS_DENIED", call.get("error_code"));
            assertEquals(0, ((Number) call.get("attempt_count")).intValue());
            assertEquals(null, call.get("submitted_at"));
        }
    }

    @Test
    void expiredPreviewRemovesItsToolRecordsAttachmentsAndCheckpointFiles() throws Exception {
        plugin();
        content = "临时预览资料".repeat(100000);
        model(origin() + "/preview", new AtomicReference<>());
        allowModelCalls = true;
        var accepted = data(preview(Map.of("input", input("读取预览资料")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText(), conversation = accepted.path("conversationId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
            assertEquals("completed", state(run));
        }
        var file = files.forRun(enterprise, run).getFirst();
        Path path = Path.of("target/p04-plugin-execution-files").resolve(file.storageKey()).toAbsolutePath();
        assertTrue(Files.exists(path));
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)).set(AgentConversationRow::getCreatedAt, (Timestamp.from(Instant.now().minusSeconds(8 * 86400)))));
        previewRetention.clean();
        previewRetention.clean();
        assertFalse(Files.exists(path));
        assertEquals(0, count("tool_call"));
        assertEquals(0, count("run_checkpoint"));
        assertEquals(0, count("agent_run"));
        assertTrue(files.forRun(enterprise, run).isEmpty());
    }

    private JsonNode submit() throws Exception {
        allowModelCalls = true;
        return data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private String state(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
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

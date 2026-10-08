package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.project.entity.ProjectLocation;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.file.FileRetentionService;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import com.stonewu.agenteam.service.project.ProjectSandboxUsageService;
import com.stonewu.agenteam.service.project.ProjectWorkspaceLayout;
import com.stonewu.agenteam.service.project.ProjectWorkspaceStore;
import com.stonewu.agenteam.support.FileScanTestServer;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.ModelProfileFixture;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实模型协议、工作空间、容器和文件下载接口共同验证交付流程。
 */
@Import(SharedEnterpriseTestEdition.class)
class WorkspaceToolExecutionApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final FileScanTestServer SCANNER = new FileScanTestServer();
    @Autowired
    private RunMapper runs;
    @Autowired
    private ToolCallMapper calls;
    @Autowired
    private ProjectWorkspaceStore workspaces;
    @Autowired
    private ProjectMetadataService projects;
    @Autowired
    private ProjectWorkspaceLayout layout;
    @Autowired
    private ProjectSandboxUsageService sandboxUsage;
    @Autowired
    private FileInspectionWorker inspector;
    @Autowired
    private FileMapper files;
    @Autowired
    private FileRetentionService fileRetention;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) {
        ENVIRONMENT.properties(properties);
        properties.add("execution.workspace-files-root", () -> "target/workspace-api/files");
        properties.add("execution.workspace-root", () -> "target/workspace-api/framework");
        properties.add("execution.state-root", () -> "target/workspace-api/states");
        properties.add("files.scan.host", () -> "127.0.0.1");
        properties.add("files.scan.port", SCANNER::port);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        SCANNER.close();
        ENVIRONMENT.close();
    }

    @Test
    void createsExecutesExportsAndContinuesFilesInTheNextRun() throws Exception {
        var step = new AtomicInteger();
        var fileId = new AtomicReference<String>();
        var read = new AtomicReference<String>();
        var edited = new AtomicReference<String>();
        var isolated = new AtomicReference<JsonNode>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            switch (step.getAndIncrement()) {
                case 0 ->
                    tool(exchange, "create", "write_file", Map.of("path", "work/make_report.py", "content", "from pathlib import Path\nPath('outputs/report.txt').write_text('第一轮结果🙂\\n文件末尾：完整交付', encoding='utf-8')\nprint('处理完成')\n"));
                case 1 ->
                    tool(exchange, "execute", "execute", Map.of("command", "python work/make_report.py", "working_directory", ".", "timeout_seconds", 15));
                case 2 -> tool(exchange, "read", "read_file", Map.of("path", "outputs/report.txt", "start_line", 1));
                case 3 -> {
                    read.set(result(input, "read").path("content").asText());
                    tool(exchange, "export", "export_file", Map.of("path", "outputs/report.txt"));
                }
                case 4 -> {
                    fileId.set(result(input, "export").path("fileId").asText());
                    answer(exchange, "文件已生成并作为附件交付。");
                }
                case 5 ->
                    tool(exchange, "edit", "edit_file", Map.of("path", "outputs/report.txt", "old_text", "第一轮", "new_text", "第二轮"));
                case 6 -> tool(exchange, "reread", "read_file", Map.of("path", "outputs/report.txt"));
                case 7 -> {
                    edited.set(result(input, "reread").path("content").asText());
                    answer(exchange, "已修改上轮工作文件。");
                }
                case 8 -> tool(exchange, "other", "read_file", Map.of("path", "outputs/report.txt"));
                default -> {
                    isolated.set(result(input, "other"));
                    answer(exchange, "此对话没有该文件。");
                }
            }
        };
        allowModelCalls = true;
        var accepted = submit("auto_approve");
        finish(accepted.path("runId").asText());
        assertEquals("第一轮结果🙂\n文件末尾：完整交付", read.get());
        assertNotNull(fileId.get());
        assertFalse(fileId.get().isBlank());
        assertEquals(0, count("run_approval"));
        assertEquals(List.of("write_file", "execute", "read_file", "export_file"), calls.forRun(enterprise, accepted.path("runId").asText()).stream().map(call -> call.toolName()).toList());
        assertEquals(read.get(), download(fileId.get()));
        var conversation = accepted.path("conversationId").asText();
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertTrue(snapshot.toString().contains(fileId.get()), "实际附件必须出现在对话中");
        var next = data(write(base() + "/conversations/" + conversation + "/messages", input("继续修改上轮文件"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        finish(next.path("runId").asText());
        assertEquals("第二轮结果🙂\n文件末尾：完整交付", edited.get());
        assertEquals(read.get(), download(fileId.get()), "之后修改工作文件不能改变已经交付的附件");
        var other = submit("auto_approve");
        finish(other.path("runId").asText());
        assertTrue(isolated.get().path("isError").asBoolean(), "另一个对话不能用相同路径读取原对话文件");
        var conversations = databaseAccess.mapper(ConversationSqlMapper.class);
        conversations.update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise)
            .eq(AgentConversationRow::getId, conversation).set(AgentConversationRow::getStatus, "deleted").set(AgentConversationRow::getDeletedAt, Instant.now().minus(Duration.ofDays(1))));
        fileRetention.clean();
        assertTrue(files.find(enterprise, fileId.get(), false).isPresent(), "可恢复的对话保留交付文件");
        conversations.update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise)
            .eq(AgentConversationRow::getId, conversation).set(AgentConversationRow::getDeletedAt, Instant.now().minus(Duration.ofDays(31))));
        fileRetention.clean();
        assertTrue(files.find(enterprise, fileId.get(), false).isEmpty(), "超过恢复时间的对话清理交付文件");
    }

    @Test
    void writeWaitsForTheExistingApprovalAndExecutesOnlyAfterConfirmation() throws Exception {
        var step = new AtomicInteger();
        modelResponse = exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (step.getAndIncrement() == 0) {
                tool(exchange, "approved-write", "write_file", Map.of("path", "work/approved.txt", "content", "已确认的写入"));
            } else {
                answer(exchange, "已创建文件。");
            }
        };
        allowModelCalls = true;
        var accepted = submit("default");
        String runId = accepted.path("runId").asText();
        finish(runId);
        var run = runs.find(enterprise, runId, false).orElseThrow();
        assertEquals("waiting_approval", run.status());
        assertFalse(Files.exists(projectMarker(run)));
        var approval = data(mvc.perform(get(base() + "/runs/" + runId + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", "approve", "requestHash", approval.path("requestHash").asText()),
            approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        finish(runId);
        assertEquals("completed", runs.find(enterprise, runId, false).orElseThrow().status());
        assertEquals(1, calls.forRun(enterprise, runId).size());
        assertEquals("succeeded", calls.forRun(enterprise, runId).getFirst().status());
        assertTrue(Files.exists(projectMarker(run)));
    }

    @Test
    void commandFailureReturnsToTheAssistantWhichCorrectsItAndCompletesTheTask() throws Exception {
        var step = new AtomicInteger();
        var failure = new AtomicReference<JsonNode>();
        var recovered = new AtomicReference<String>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            switch (step.getAndIncrement()) {
                case 0 ->
                    tool(exchange, "bad-command", "execute", Map.of("command", "printf '需要修正命令' >&2; exit 7", "timeout_seconds", 15));
                case 1 -> {
                    failure.set(result(input, "bad-command"));
                    tool(exchange, "fixed-command", "execute", Map.of("command", "printf '已修正' > outputs/recovered.txt", "timeout_seconds", 15));
                }
                case 2 -> tool(exchange, "read-recovered", "read_file", Map.of("path", "outputs/recovered.txt"));
                default -> {
                    recovered.set(result(input, "read-recovered").path("content").asText());
                    answer(exchange, "已根据工具错误修正命令并完成任务。");
                }
            }
        };
        allowModelCalls = true;
        var accepted = submit("auto_approve");
        String runId = accepted.path("runId").asText();
        finish(runId);
        assertTrue(failure.get().path("isError").asBoolean());
        assertEquals(7, failure.get().path("exitCode").asInt());
        assertEquals("需要修正命令", failure.get().path("stderr").asText());
        assertEquals("已修正", recovered.get());
        assertEquals("completed", runs.find(enterprise, runId, false).orElseThrow().status());
        assertEquals(List.of("failed", "succeeded", "succeeded"), calls.forRun(enterprise, runId).stream().map(call -> call.status()).toList());
    }

    @Test
    void invalidArgumentsReturnToTheAssistantAndOnlyTheCorrectedWriteAsksForApproval() throws Exception {
        var step = new AtomicInteger();
        var failure = new AtomicReference<String>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            switch (step.getAndIncrement()) {
                case 0 ->
                    tool(exchange, "invalid-write", "write_file", Map.of("path", "work/corrected.txt", "content", 42));
                case 1 -> {
                    failure.set(modelTextForCall(input, "invalid-write"));
                    tool(exchange, "corrected-write", "write_file", Map.of("path", "work/corrected.txt", "content", "修正后的内容"));
                }
                default -> answer(exchange, "参数已修正，并在确认后写入文件。");
            }
        };
        allowModelCalls = true;
        var accepted = submit("default");
        String runId = accepted.path("runId").asText();
        finish(runId);
        var run = runs.find(enterprise, runId, false).orElseThrow();
        assertEquals("waiting_approval", run.status());
        assertTrue(failure.get().contains("content"), "错误结果应指出需要修正的参数");
        assertEquals(1, count("run_approval"), "只为修正后的有效写入申请确认");
        assertEquals("failed", calls.forRun(enterprise, runId).getFirst().status());
        assertEquals("TOOL_ARGUMENTS_INVALID", calls.forRun(enterprise, runId).getFirst().errorCode());
        assertFalse(Files.exists(projectMarker(run)));
        var approval = data(mvc.perform(get(base() + "/runs/" + runId + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", "approve", "requestHash", approval.path("requestHash").asText()),
            approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        finish(runId);
        assertEquals("completed", runs.find(enterprise, runId, false).orElseThrow().status());
        assertEquals(List.of("failed", "succeeded"), calls.forRun(enterprise, runId).stream().map(call -> call.status()).toList());
        var saved = workspaces.read(enterprise, admin, run.conversationId(), snapshot -> snapshot.files().bytes("work/corrected.txt"));
        assertEquals("修正后的内容", new String(saved, StandardCharsets.UTF_8));
    }

    @Test
    void commandTimeoutReturnsItsOutputAndTheAssistantCanContinueUsingFileTools() throws Exception {
        configureAgent(config -> config.put("maxSteps", 0).put("timeoutSeconds", 0));
        var step = new AtomicInteger();
        var timeout = new AtomicReference<JsonNode>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            switch (step.getAndIncrement()) {
                case 0 ->
                    tool(exchange, "slow-command", "execute", Map.of("command", "printf '已开始处理'; sleep 30", "timeout_seconds", 6));
                case 1 -> {
                    timeout.set(result(input, "slow-command"));
                    tool(exchange, "alternative", "write_file", Map.of("path", "outputs/alternative.txt", "content", "已改用文件工具完成。"));
                }
                default -> answer(exchange, "命令超时后已改用文件工具继续完成任务。");
            }
        };
        allowModelCalls = true;
        var accepted = submit("auto_approve");
        String runId = accepted.path("runId").asText();
        finish(runId);
        assertTrue(timeout.get().path("isError").asBoolean());
        assertTrue(timeout.get().path("timedOut").asBoolean());
        assertEquals(124, timeout.get().path("exitCode").asInt());
        assertEquals("已开始处理", timeout.get().path("stdout").asText());
        assertEquals("completed", runs.find(enterprise, runId, false).orElseThrow().status());
        assertEquals(List.of("failed", "succeeded"), calls.forRun(enterprise, runId).stream().map(call -> call.status()).toList());
    }

    @Test
    void usesUploadedInputsAndSavedToolReferencesWithoutLosingSourceAccessChecks() throws Exception {
        String profile = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, admin, "workspace-model", 1, "工作文件模型",
            "openai", "test-model", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "WORKSPACE_TEST_SECRET",
            new ModelCapabilities(true, true, 8192, 262144, List.of("text")), true)), key -> "isolated-workspace-secret").getFirst();
        configureAgent(config -> {
            config.put("attachmentsEnabled", true);
            config.put("modelProfileId", profile);
        });
        String original = "资料内容🙂".repeat(1500) + "文件末尾的答案是四十二";
        String file = upload(original), path = "inputs/" + file + "/source.txt";
        var step = new AtomicInteger();
        var exported = new AtomicReference<String>();
        var reference = new AtomicReference<String>();
        modelResponse = exchange -> {
            var input = json.readTree(exchange.getRequestBody());
            switch (step.getAndIncrement()) {
                case 0 -> tool(exchange, "input-read", "read_file", Map.of("path", path));
                case 1 -> {
                    reference.set(result(input, "input-read").path("_toolResultFile").path("path").asText());
                    String command = "python - <<'PY'\nfrom pathlib import Path\nfull = Path('" + path + "').read_text()\nfragment = Path('" + reference.get() + "').read_text()\nassert full.startswith(fragment)\nPath('outputs/copied.txt').write_text(full)\nPY";
                    tool(exchange, "input-process", "execute", Map.of("command", command, "result_paths", List.of(reference.get()), "timeout_seconds", 15));
                }
                case 2 -> tool(exchange, "input-export", "export_file", Map.of("path", "outputs/copied.txt"));
                default -> {
                    exported.set(result(input, "input-export").path("fileId").asText());
                    answer(exchange, "已处理完整输入文件。");
                }
            }
        };
        allowModelCalls = true;
        var input = (ObjectNode) json.valueToTree(input("读取文件并保留完整内容"));
        input.putArray("attachmentIds").add(file);
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "approvalPolicy", "auto_approve", "input", input), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        finish(accepted.path("runId").asText());
        assertTrue(reference.get().startsWith("tool-results/"));
        assertEquals(original, download(exported.get()));
        files.deleted(files.find(enterprise, file, false).orElseThrow(), Instant.now());
        mvc.perform(get(base() + "/files/" + exported.get() + "/download").cookie(cookie)).andExpect(status().isNotFound());
    }

    private String upload(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var declaration = json.createObjectNode().put("purpose", "attachment").putNull("resourceId").put("name", "source.txt").put("sizeBytes", bytes.length).put("mediaType", "text/plain").put("sha256", hash);
        String id = data(write(base() + "/files", declaration, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + id + "/content").cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType("text/plain").content(bytes)).andExpect(status().isNoContent());
        write(base() + "/files/" + id + "/complete", Map.of("sizeBytes", bytes.length, "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertTrue(inspector.runNext());
        return id;
    }

    private JsonNode submit(String policy) throws Exception {
        return data(write(base() + "/conversations", Map.of("agentId", agent, "approvalPolicy", policy, "input", input("处理工作文件")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private void finish(String id) {
        var lease = lifecycle.claim("workspace-api-test").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        assertEquals(id, run.id());
        sandboxUsage.started(lease);
        try (var task = tasks.create(run, lease)) {
            task.completion().block(Duration.ofSeconds(25));
            task.saveCheckpoint();
            if (!task.waiting()) {
                lifecycle.finish(lease, task.cancelled() ? "cancelled" : "completed", null, null);
            }
        } finally {
            sandboxUsage.release(lease);
        }
    }

    private String download(String id) throws Exception {
        var issued = data(mvc.perform(get(base() + "/files/" + id + "/download").cookie(cookie)).andExpect(status().isOk()).andReturn());
        var pending = mvc.perform(get(issued.path("url").asText()).cookie(cookie)).andReturn();
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode result(JsonNode input, String id) throws IOException {
        for (var message : input.path("messages")) {
            if (id.equals(message.path("tool_call_id").asText())) {
                var content = message.path("content");
                if (content.isTextual()) {
                    return json.readTree(content.asText());
                }
                for (var part : content) {
                    if (part.path("text").isTextual()) {
                        return json.readTree(part.path("text").asText());
                    }
                }
            }
        }
        throw new IllegalStateException("模型请求中缺少对应工具结果");
    }

    private Path projectMarker(RunRecord run) {
        var project = projects.require(run.enterpriseId(), run.userId(), run.executionConfig().path("workspaceProjectId").asText());
        return layout.state(new ProjectLocation(project.getWorkspaceId(), project.getId(), run.enterpriseId(), run.userId(), project.getDirectoryPath()));
    }

    private void tool(HttpExchange exchange, String id, String name, Object arguments) throws IOException {
        frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", id, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
    }

    private void answer(HttpExchange exchange, String text) throws IOException {
        frame(exchange, Map.of("content", text), "stop");
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        String chunk = json.writeValueAsString(Map.of("id", "workspace-stream", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish))));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("data: " + chunk + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}

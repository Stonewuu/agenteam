package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.plugin.PlatformBusinessTools;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际对话确认与业务事务共同验证，不把适配器登记视为工具已经可用。
 */
@Import(SharedEnterpriseTestEdition.class)
class PlatformBuiltinToolApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired
    private RunMapper runs;
    @Autowired
    private ToolCallMapper calls;
    @Autowired
    private ToolCallTransactions transactions;
    @Autowired
    private ToolExecutionService toolExecution;
    @Autowired
    private ExecutionToolCatalog catalog;
    @MockitoSpyBean
    private PlatformBusinessTools business;
    private final AtomicReference<String> modelResult = new AtomicReference<>();

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) {
        ENVIRONMENT.properties(properties);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void actualBuiltinDirectoryIncludesUsableBusinessTools() throws Exception {
        var result = data(mvc.perform(get(base() + "/plugins/builtins").cookie(cookie)).andExpect(status().isOk()).andReturn());
        var codes = new HashSet<String>();
        for (var plugin : result) {
            schemas.validate("BuiltinPlugin", plugin);
            codes.add(plugin.path("code").asText());
            assertFalse(plugin.path("toolNames").isEmpty());
        }
        assertTrue(codes.containsAll(Set.of("web_read", "platform_basics", "todo_management", "schedule_management")));
    }

    @Test
    void todoCreationRequiresConfirmationAndIsVisibleInTheRealTodoList() throws Exception {
        String run = prepare("todo_management", "todo_create", Map.of("title", "工具创建的待办", "description", "需要整理的事项", "dueDate", "2027-01-02"));
        var approval = data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        assertEquals("平台业务插件 · 创建待办", approval.path("summary").path("title").asText());
        var conversation = data(mvc.perform(get(base() + "/conversations/" + runs.find(enterprise, run, false).orElseThrow().conversationId()).cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertTrue(conversation.toString().contains("平台业务插件 · 创建待办"));
        assertEquals(0, todos().size());
        decision(run, "approve");
        finish(run);
        var todo = todos().get(0);
        assertEquals("工具创建的待办", todo.path("title").asText());
        assertEquals(admin, todo.path("owner").path("id").asText());
        assertEquals("message", todo.path("sourceType").asText());
        assertEquals("2027-01-02", todo.path("dueDate").asText());
        assertTrue(modelResult.get().contains("待办已创建"));
        assertEquals("succeeded", calls.forRun(enterprise, run).getFirst().status());
    }

    @Test
    void rejectedOrRevokedOperationsDoNotCreateBusinessRecords() throws Exception {
        String rejected = prepare("todo_management", "todo_create", Map.of("title", "不得创建"));
        decision(rejected, "reject");
        finish(rejected);
        assertTrue(todos().isEmpty());
        String revoked = prepare("todo_management", "todo_create", Map.of("title", "失去资格后不得创建"));
        decision(revoked, "approve");
        permissions.replaceUserRoles(admin, enterprise, Set.of(), Instant.now());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(revoked));
        }
        assertNotEquals("completed", state(revoked));
        assertEquals(0, count("todo_item"));
    }

    @Test
    void failureAfterBusinessMutationRollsBackTheRecordAndItsHistory() throws Exception {
        String run = prepare("todo_management", "todo_create", Map.of("title", "事务回滚验证"));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("模拟业务写入后保存结果之前失败");
        }).when(business).call(any(), eq("todo_create"), any());
        decision(run, "approve");
        finish(run);
        assertTrue(todos().isEmpty());
        assertEquals(0, count("todo_history"));
        var call = calls.forRun(enterprise, run).getFirst();
        assertEquals("failed", call.status());
        assertNull(call.submittedAt(), "业务与提交标记应一起回滚，不能冒充结果未知");
    }

    @Test
    void simultaneousReplayCannotDuplicateRecordsAndLaterReplayReadsTheSavedResult() throws Exception {
        String id = prepare("todo_management", "todo_create", Map.of("title", "只创建一次"));
        decision(id, "approve");
        var lease = lifecycle.claim("builtin-replay-test").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> invoke(run, lease));
            var second = executor.submit(() -> invoke(run, lease));
            JsonNode saved = null;
            for (var result : List.of(first, second)) {
                try {
                    var value = result.get();
                    if (saved == null) {
                        saved = value;
                    } else {
                        assertEquals(saved, value);
                    }
                } catch (ExecutionException failure) {
                    // 同一执行资格下的并发调用可能在第一笔事务开始前被原有执行保护拒绝。
                    assertInstanceOf(IllegalStateException.class, failure.getCause());
                    assertEquals("本次工具操作已经开始或结束", failure.getCause().getMessage());
                }
            }
            assertNotNull(saved);
            assertEquals(saved, invoke(run, lease));
        }
        assertEquals(1, todos().size());
        var call = calls.forRun(enterprise, id).getFirst();
        assertEquals(1, call.attemptCount());
        assertFalse(transactions.failed(lease, call.id(), "SIMULATED_FAILURE", "模拟提交后出现错误"));
        assertEquals("succeeded", calls.find(enterprise, call.id(), false).orElseThrow().status());
        lifecycle.finish(lease, "completed", null, null);
    }

    @Test
    void scheduleCreationUsesTheRealHireAndOnlyChangesFutureSettings() throws Exception {
        String hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow().id();
        String run = prepare("schedule_management", "schedule_create", Map.of("name", "每天整理资料", "hireId", hire,
            "inputText", "整理当天资料", "frequency", "daily", "localTime", "09:00", "enabled", false));
        assertTrue(schedules().isEmpty());
        decision(run, "approve");
        finish(run);
        var saved = schedules().get(0);
        assertEquals("每天整理资料", saved.path("name").asText());
        assertEquals(hire, saved.path("hireId").asText());
        assertFalse(saved.path("enabled").asBoolean());
        assertEquals("Asia/Shanghai", saved.path("timezone").asText());
        assertFalse(saved.path("agentVersionId").asText().isBlank());
    }

    @Test
    void defaultBuiltinCanBeSelectedPublishedAndExecutedWithoutCreatingPluginDraft() throws Exception {
        var choices = data(mvc.perform(get(base() + "/resources/usable-versions").param("kind", "plugin").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items");
        String selected = null;
        for (var choice : choices) {
            if (choice.path("name").asText().equals("平台基础")) {
                selected = choice.path("versionId").asText();
            }
        }
        assertNotNull(selected, "内置插件必须直接出现在智能体使用的版本选择接口中");
        String pluginVersion = selected;
        configureAgent(value -> value.set("pluginVersionIds", json.valueToTree(List.of(pluginVersion))));
        String run = submit();
        String alias = catalog.list(runs.find(enterprise, run, false).orElseThrow()).values().stream()
            .filter(tool -> tool.definition().name().equals("platform_time")).findFirst().orElseThrow().alias();
        model(Map.of(), alias);
        finish(run);
        assertTrue(modelResult.get().contains("Asia/Shanghai"));
        assertEquals(0, count("run_approval"));
        assertEquals("read", calls.forRun(enterprise, run).getFirst().operationClass());
    }

    @Test
    void notificationActionToolRequiresConfirmationAndPersistsExplicitRecipientsWithoutAnAgent() throws Exception {
        String run = prepare("schedule_management", "schedule_action_create", Map.of("name", "每天例会通知", "frequency", "daily",
            "localTime", "09:15", "enabled", false, "action", Map.of("type", "notification.send", "schemaVersion", 1,
                "config", Map.of("title", "参加例会", "body", "请准备会议资料。", "recipients", List.of(Map.of("userId", admin, "connectionIds", List.of()))))));
        assertTrue(schedules().isEmpty());
        assertEquals(0, count("notification"));
        decision(run, "approve");
        finish(run);
        var saved = schedules().get(0);
        assertEquals("notification.send", saved.at("/action/type").asText());
        assertEquals(admin, saved.at("/action/recipients/0/userId").asText());
        assertTrue(saved.path("hireId").isNull());
        assertTrue(saved.path("agentVersionId").isNull());
        assertTrue(modelResult.get().contains("定时任务已创建"));
        assertEquals("succeeded", calls.forRun(enterprise, run).getFirst().status());
    }

    @Test
    void previewAndScheduledModesExposeOnlyTheReadPartOfBusinessPlugins() throws Exception {
        bind("todo_management", "todo_list", "todo_create");
        String id = submit();
        var run = runs.find(enterprise, id, false).orElseThrow();
        assertEquals(2, catalog.list(run).values().stream().filter(tool -> tool.resourceKind().equals("plugin")).count());
        for (String mode : List.of("preview", "scheduled", "manual_schedule")) {
            var variant = new RunRecord(run.id(), run.enterpriseId(), run.conversationId(), run.userId(), run.inputMessageId(), run.outputMessageId(),
                run.agentVersionId(), mode, run.status(), run.executionConfig(), run.currentAttemptNo(), run.maxAttempts(), run.leaseVersion(),
                run.hasStepErrors(), run.lastSequence(), run.startedAt(), run.finishedAt(), run.cancelRequestedAt(), run.nextAttemptAt(), run.errorCode(), run.errorMessage(), run.createdAt());
            var tools = catalog.list(variant);
            assertEquals(List.of("todo_list"), tools.values().stream().filter(tool -> tool.resourceKind().equals("plugin")).map(tool -> tool.definition().name()).toList());
        }
    }

    private JsonNode invoke(RunRecord run, JobLease lease) {
        var call = calls.forRun(enterprise, run.id()).getFirst();
        var binding = catalog.list(run).values().stream().filter(tool -> tool.resourceId().equals(call.resourceId()) && tool.definition().name().equals(call.toolName())).findFirst().orElseThrow();
        return toolExecution.invoke(run, lease, binding, call.frameworkSessionId(), transactions.originalCall(call), Duration.ofSeconds(30), new AtomicReference<ToolCallControl>(), () -> false);
    }

    private String prepare(String code, String tool, Map<String, Object> args) throws Exception {
        bind(code, tool);
        model(args, tool);
        String run = submit();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("waiting_approval") || terminal(run));
        }
        assertEquals("waiting_approval", state(run), () -> diagnostic(run));
        return run;
    }

    private void bind(String code, String... tools) throws Exception {
        ObjectNode config = json.createObjectNode().put("icon", "Box").put("color", "teal").put("pluginType", "builtin").put("builtinCode", code)
            .putNull("transport").putNull("endpoint").putNull("credentialId").put("timeoutSeconds", 30);
        config.set("enabledToolNames", json.valueToTree(List.of(tools)));
        String plugin = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "平台业务插件", "description", "调用实际业务服务", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String published = data(change(HttpMethod.POST, base() + "/resources/" + plugin + "/publish", Map.of("releaseNote", "发布业务工具"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(value -> value.set("pluginVersionIds", json.valueToTree(List.of(published))));
    }

    private void model(Map<String, Object> args, String alias) {
        var iteration = new AtomicInteger();
        modelResult.set(null);
        allowModelCalls = true;
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "business-call", "type", "function",
                    "function", Map.of("name", alias, "arguments", json.writeValueAsString(args))))), "tool_calls");
            } else {
                modelResult.set(request.toString());
                frame(exchange, Map.of("content", "已根据实际工具结果完成处理。"), "stop");
            }
        };
    }

    private String submit() throws Exception {
        return data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn()).path("runId").asText();
    }

    private void decision(String run, String choice) throws Exception {
        var approval = data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn()).get(0);
        assertTrue(approval.path("summary").path("description").asText().contains("平台中的待办或定时任务"));
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", choice, "requestHash", approval.path("requestHash").asText()), approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
    }

    private void finish(String run) throws Exception {
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertEquals("completed", state(run), () -> diagnostic(run));
    }

    private String state(String run) {
        return runs.find(enterprise, run, false).orElseThrow().status();
    }

    private String diagnostic(String run) {
        return runs.find(enterprise, run, false).orElseThrow().toString();
    }

    private boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(state(run));
    }

    private JsonNode todos() throws Exception {
        return data(mvc.perform(get(base() + "/todos").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items");
    }

    private JsonNode schedules() throws Exception {
        return data(mvc.perform(get(base() + "/schedules").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items");
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", "business-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}

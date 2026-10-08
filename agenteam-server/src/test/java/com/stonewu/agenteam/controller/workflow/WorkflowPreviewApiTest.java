package com.stonewu.agenteam.controller.workflow;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.mapper.test.execution.RunStepFixtureMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.request.ApprovalDecisionRequest;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.model.workflow.request.WorkflowPreviewInput;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.service.todo.TodoManagementService;
import com.stonewu.agenteam.service.todo.TodoQueryService;
import com.stonewu.agenteam.service.workflow.WorkflowAgentNodeExecutor;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedCaseInsensitiveMap;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 独立工作流测试使用真实资源和执行记录，不保存草稿、不创建或雇佣假员工。
 */
@Import(SharedEnterpriseTestEdition.class)
class WorkflowPreviewApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private RunSubmissionService submissions;

    @Autowired
    private RunApprovalService decisions;

    @Autowired
    private RunApprovalMapper approvalRecords;

    @Autowired
    private ResourceGrantService grants;

    @Autowired
    private PreviewRetentionService retention;

    @Autowired
    private PreviewRetentionMapper retentionRecords;

    @Autowired
    private TodoManagementService todos;

    @Autowired
    private TodoQueryService todoQueries;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private WorkflowAgentNodeExecutor nodeAgents;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p06-workflow-preview-workspace");
        registry.add("execution.state-root", () -> "target/p06-workflow-preview-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void testsUnsavedContentOnceAndKeepsTheWorkflowIdentityWithoutCreatingAnEmployee() throws Exception {
        var original = graph(false);
        String resource = create(original);
        int employees = employees(), hiresBefore = count("agent_hire");
        var draft = original.deepCopy();
        ((ObjectNode) draft.at("/nodes/1/config/fields/0")).put("template", "仅本次：${input.text}");
        var body = Map.of("draft", draft, "input", Map.of("text", "测试正文"));
        String key = UUID.randomUUID().toString();
        var accepted = data(change(HttpMethod.POST, path(resource), body, "1", key).andExpect(status().isAccepted()).andReturn());
        var repeated = data(change(HttpMethod.POST, path(resource), body, "1", key).andExpect(status().isAccepted()).andReturn());
        assertEquals(accepted, repeated);
        String run = accepted.path("runId").asText();
        assertEquals(employees, employees());
        assertEquals(hiresBefore, count("agent_hire"));
        var conversation = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ConversationSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getAgentId, AgentConversationRow::getPreviewResourceId, AgentConversationRow::getAgentVersionId, AgentConversationRow::getHireId).eq(AgentConversationRow::getId, (accepted.path("conversationId").asText()))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("agent_id", fixtureValues.get("agent_id"));
            fixtureRow.put("preview_resource_id", fixtureValues.get("preview_resource_id"));
            fixtureRow.put("agent_version_id", fixtureValues.get("agent_version_id"));
            fixtureRow.put("hire_id", fixtureValues.get("hire_id"));
            return fixtureRow;
        }).toList());
        assertNull(conversation.get("agent_id"));
        assertNull(conversation.get("agent_version_id"));
        assertNull(conversation.get("hire_id"));
        assertEquals(resource, conversation.get("preview_resource_id"));
        assertEquals(original, json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getResourceId, (resource))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList())));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, (resource)))));
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        assertEquals(callsBefore, modelCalls.get());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ConversationSnapshot", snapshot);
        assertTrue(snapshot.at("/conversation/agentId").isNull());
        assertFalse(snapshot.at("/conversation/canContinue").asBoolean());
        assertEquals("仅本次：测试正文", snapshot.at("/messages/1/content").asText());
        var config = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getExecutionConfigJson).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getExecutionConfigJson()).toList()));
        assertEquals(resource, config.path("workflowId").asText());
        assertFalse(config.has("agentId"));
        assertEquals(100, config.at("/limits/maxSteps").asInt());
        assertEquals(1800, config.at("/limits/timeoutSeconds").asInt());
        assertEquals("preview", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getMode).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getMode()).toList()));
    }

    @Test
    void rejectsInvalidInputsStaleRevisionsWrongResourceKindsAndAnotherEnterpriseBeforeSubmission() throws Exception {
        var graph = graph(false);
        String resource = create(graph);
        change(HttpMethod.POST, path(resource), Map.of("draft", graph, "input", Map.of()), "1", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        var conflict = change(HttpMethod.POST, path(resource), Map.of("draft", graph, "input", Map.of("text", "测试")), "9", UUID.randomUUID().toString()).andExpect(status().isConflict()).andReturn();
        assertEquals("VERSION_CONFLICT", json.readTree(conflict.getResponse().getContentAsString()).at("/error/code").asText());
        change(HttpMethod.POST, path(agent), Map.of("draft", graph, "input", Map.of("text", "测试")), "2", UUID.randomUUID().toString()).andExpect(status().isNotFound());
        String other = provisioning.create("另一测试企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        change(HttpMethod.POST, "/api/v1/enterprises/" + other + "/workflows/" + resource + "/preview", Map.of("draft", graph, "input", Map.of("text", "测试")), "1", UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertEquals(0, count("agent_run"));
        assertEquals(0, reserved());
    }

    @Test
    void workflowPreviewPermissionAloneAllowsConfirmationAndStoppingOwnTest() throws Exception {
        var graph = graph(true);
        String resource = create(graph);
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "workflow-preview-only", "仅测试工作流", "不授予员工运行或预览权限", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("workflow.preview", "conversation.view"));
        var user = EnterpriseTestData.member(users, permissions, enterprise, "workflow-preview-" + UUID.randomUUID(), "工作流测试所用的独立口令", "工作流测试者", List.of(role));
        grants.replace(actor(admin), resource, 1, List.of(new ResourceGrantSpec("user", user.id(), "edit")));
        var actor = actor(user.id());
        assertFalse(actor.permissions().contains("agent.preview"));
        assertFalse(actor.permissions().contains("agent.run"));
        var input = new WorkflowPreviewInput(graph, json.valueToTree(Map.of("text", "等待决定")));
        var accepted = submissions.workflowPreview(actor, resource, input);
        String run = accepted.runId();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("waiting_approval") || terminal(run));
        }
        assertEquals("waiting_approval", state(run));
        var approval = approvalRecords.forRun(enterprise, run).getFirst();
        decisions.authorizeDecision(actor, approval.id());
        decisions.decide(actor, approval.id(), approval.revision(), new ApprovalDecisionRequest("approve", approval.requestHash(), null));
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        var second = submissions.workflowPreview(actor, resource, input);
        assertEquals("cancelled", lifecycle.cancel(actor, second.runId()).status());
        assertEquals(0, reserved());
    }

    @Test
    void disablingTheWorkflowCountsAndStopsItsPreviewAndExpiredTestsCanBeRemoved() throws Exception {
        var graph = graph(false);
        String resource = create(graph);
        var accepted = data(change(HttpMethod.POST, path(resource), Map.of("draft", graph, "input", Map.of("text", "测试")), "1", UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText(), conversation = accepted.path("conversationId").asText();
        var impact = data(mvc.perform(get(base() + "/resources/" + resource + "/impact").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, impact.path("activeRunCount").asInt());
        change(HttpMethod.PATCH, base() + "/resources/" + resource + "/status", Map.of("status", "disabled"), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("cancelled", state(run));
        assertEquals(0, reserved());
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)).set(AgentConversationRow::getCreatedAt, (Timestamp.from(Instant.now().minusSeconds(8 * 86400)))));
        retention.clean();
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ConversationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getId, (run)))));
    }

    @Test
    void invalidFinalReferencesAndOversizedTextFailTheEndNodeBeforePublishingAnyResult() throws Exception {
        var original = graph(false);
        String resource = create(original);
        var outputs = List.of(Map.of("text", "不可发布", "attachments", List.of(Map.of("id", UUID.randomUUID().toString()))), Map.of("text", "不可发布", "citations", Map.of("chunkId", UUID.randomUUID().toString())), Map.of("text", "${input.text}${input.text}${input.text}"));
        for (int index = 0; index < outputs.size(); index++) {
            var draft = original.deepCopy();
            ((ObjectNode) draft.at("/nodes/2/config")).set("outputMapping", json.valueToTree(outputs.get(index)));
            var accepted = data(change(HttpMethod.POST, path(resource), Map.of("draft", draft, "input", Map.of("text", index == 2 ? "甲".repeat(80000) : "测试")), "1", UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
            String run = accepted.path("runId").asText();
            try (var worker = new RunWorker(lifecycle, tasks)) {
                worker.poll();
                await(() -> terminal(run));
            }
            assertEquals("failed", state(run));
            assertEquals(index == 2 ? "WORKFLOW_OUTPUT_TOO_LARGE" : "WORKFLOW_REFERENCE_INVALID", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getErrorCode()).toList()));
            assertEquals("failed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunStepFixtureMapper.class).workflowPreviewApiInvalidFinalReferencesAndOversizedTextFailTheEndNodeBeforePublishingAnyResultObject(run)));
            var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
            assertFalse(snapshot.at("/messages/1/blocks").findValuesAsText("type").contains("attachment"));
            assertFalse(snapshot.at("/messages/1/blocks").findValuesAsText("type").contains("citation"));
            assertFalse(snapshot.at("/messages/1/content").asText().contains("不可发布"));
        }
    }

    @Test
    void standalonePreviewCanExecuteANodeWithDynamicSubagentsEnabled() throws Exception {
        configureAgent(config -> config.put("dynamicSubagentEnabled", true));
        allowModelCalls = true;
        modelResponse = exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var response = Map.of("id", "workflow-preview-model", "choices", List.of(Map.of("index", 0, "delta", Map.of("content", "独立模型节点已完成"), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        var captured = new AtomicReference<Throwable>();
        doAnswer(invocation -> ((Mono<?>) invocation.callRealMethod()).doOnError(captured::set)).when(nodeAgents).execute(any(), any(), any(), any(), any());
        var draft = graph(false);
        ((ObjectNode) draft.at("/nodes/1")).put("type", "agent").set("config", json.valueToTree(Map.of("agentVersionId", version, "inputMapping", Map.of("text", "${input.text}"))));
        String resource = create(draft);
        var accepted = data(change(HttpMethod.POST, path(resource), Map.of("draft", draft, "input", Map.of("text", "独立模型节点")), "1", UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        if (captured.get() != null) {
            throw new AssertionError("独立测试中的模型节点没有完成", captured.get());
        }
        assertCompleted(run);
        assertEquals(callsBefore + 1, modelCalls.get());
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals("独立模型节点已完成", snapshot.at("/messages/1/content").asText());
    }

    @Test
    void confirmedWorkflowTodoKeepsItsBodyAndHistoryWhenTheExpiredSourceIsPermanentlyRemoved() throws Exception {
        var draft = graph(false);
        String resource = create(draft);
        var accepted = data(change(HttpMethod.POST, path(resource), Map.of("draft", draft, "input", Map.of("text", "待办来源结果")), "1", UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText(), conversation = accepted.path("conversationId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> terminal(run));
        }
        assertCompleted(run);
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
        String message = snapshot.at("/messages/1/id").asText();
        var todo = todos.create(actor(admin), new TodoWriteRequest("确认后保存的工作流事项", "用户确认保留的摘要", admin, null, null, "normal", "workflow", conversation, message, run));
        assertTrue(todo.sourceAccessible());
        assertEquals("workflow", todo.sourceType());
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)).set(AgentConversationRow::getCreatedAt, (Timestamp.from(Instant.now().minusSeconds(8 * 86400)))));
        var before = Instant.now().minusSeconds(7 * 86400);
        var preview = new PreviewRetentionMapper.Preview(enterprise, admin, conversation);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            retentionRecords.remove(preview, before);
            transaction.setRollbackOnly();
        });
        assertEquals(conversation, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(TodoTableMapper.class).selectList(new LambdaQueryWrapper<TodoItemRow>().select(TodoItemRow::getSourceConversationId).eq(TodoItemRow::getId, (todo.id()))).stream().map(fixtureRecord -> fixtureRecord.getSourceConversationId()).toList()));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ConversationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)))));
        retention.clean();
        retention.clean();
        var kept = todoQueries.get(actor(admin), todo.id());
        assertEquals(todo.description(), kept.description());
        assertEquals(todo.revision(), kept.revision());
        assertFalse(kept.sourceAccessible());
        assertNull(kept.sourceConversationId());
        assertEquals(1, todoQueries.history(actor(admin), todo.id(), null, 30).items().size());
        var sources = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(TodoTableMapper.class).selectMaps(new LambdaQueryWrapper<TodoItemRow>().select(TodoItemRow::getSourceConversationId, TodoItemRow::getSourceMessageId, TodoItemRow::getSourceRunId).eq(TodoItemRow::getId, (todo.id()))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("source_conversation_id", fixtureValues.get("source_conversation_id"));
            fixtureRow.put("source_message_id", fixtureValues.get("source_message_id"));
            fixtureRow.put("source_run_id", fixtureValues.get("source_run_id"));
            return fixtureRow;
        }).toList());
        assertTrue(sources.values().stream().allMatch(value -> value == null));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getId, (run)))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentMessageRow>().eq(AgentMessageRow::getId, (message)))));
    }

    private AuthContext actor(String id) {
        return new AuthContext(users.findById(id).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(id, enterprise)));
    }

    private String path(String id) {
        return base() + "/workflows/" + id + "/preview";
    }

    private String state(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    private boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(state(run));
    }

    private void assertCompleted(String run) {
        assertEquals("completed", state(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString());
    }

    private int employees() {
        return Math.toIntExact(databaseAccess.mapper(ResourceSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getEnterpriseId, (enterprise)).eq(ResourceRow::getKind, "agent")));
    }

    private String create(JsonNode graph) throws Exception {
        return data(write(base() + "/resources", Map.of("kind", "workflow", "name", "独立测试工作流", "description", "真实测试", "tagIds", List.of(), "config", graph), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private ObjectNode graph(boolean approval) {
        var start = node("start", "start", Map.of("inputSchema", Map.of("type", "object", "required", List.of("text"), "properties", Map.of("text", Map.of("type", "string")))));
        var work = node("work", "transform", Map.of("fields", List.of(Map.of("target", "text", "template", "草稿：${input.text}"))));
        var end = node("end", "end", Map.of("outputMapping", Map.of("text", approval ? "确认已处理" : "${steps.work.output.text}")));
        if (!approval) {
            return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(start, work, end), "edges", List.of(edge("start", "work", "default"), edge("work", "end", "default"))));
        }
        var confirm = node("approval", "approval", Map.of("title", "确认测试内容", "description", "核对本次输入", "inputMapping", Map.of("text", "${input.text}")));
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(start, confirm, work, end), "edges", List.of(edge("start", "approval", "default"), edge("approval", "work", "approve"), edge("approval", "end", "reject"), edge("work", "end", "default"))));
    }

    private JsonNode node(String id, String type, Object config) {
        return json.valueToTree(Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config));
    }

    private JsonNode edge(String from, String to, String branch) {
        return json.valueToTree(Map.of("edgeId", from + "-" + to, "source", from, "target", to, "branch", branch));
    }
}

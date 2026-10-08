package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.request.ConversationUpdateInput;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRow;
import com.stonewu.agenteam.service.execution.ConversationManagementService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.PluginTestServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实会话、后台执行及本地工具服务检查策略，外部业务数据不会被修改。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationApprovalPolicyApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final PluginTestServer REMOTE = new PluginTestServer();
    private final AtomicInteger executions = new AtomicInteger();
    @Autowired
    private RunMapper runs;
    @Autowired
    private ConversationMapper conversations;
    @Autowired
    private ConversationManagementService management;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", REMOTE::origin);
        registry.add("execution.workspace-root", () -> "target/approval-policy-workspace");
        registry.add("execution.state-root", () -> "target/approval-policy-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        REMOTE.close();
        ENVIRONMENT.close();
    }

    @ParameterizedTest
    @CsvSource({"default,read,false", "default,write,true", "default,destructive,true", "default,unknown,true",
        "auto_approve,read,false", "auto_approve,write,false", "auto_approve,destructive,true", "auto_approve,unknown,true",
        "full_access,read,false", "full_access,write,false", "full_access,destructive,false", "full_access,unknown,false"})
    void policyControlsActualToolExecution(String policy, String operationClass, boolean confirmation) throws Exception {
        prepareTool(operationClass);
        var request = (ObjectNode) json.valueToTree(body());
        request.put("approvalPolicy", policy);
        var accepted = data(write(base() + "/conversations", request, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        execute(run);
        var record = runs.find(enterprise, run, false).orElseThrow();
        assertEquals(confirmation ? "waiting_approval" : "completed", record.status(), record::toString);
        assertEquals(confirmation ? 0 : 1, executions.get());
        assertEquals(confirmation ? 1 : 0, count("run_approval"));
        assertEquals(policy, record.executionConfig().path("approvalPolicy").asText());
        var conversation = snapshot(accepted.path("conversationId").asText()).path("conversation");
        assertEquals(policy, conversation.path("approvalPolicy").asText());
        schemas.validate("Conversation", conversation);
    }

    @Test
    void oldClientsKeepDefaultAndSavedPolicyAppliesToNextRunOnly() throws Exception {
        prepareTool("read");
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String conversation = accepted.path("conversationId").asText(), run = accepted.path("runId").asText();
        String revision = snapshot(conversation).at("/conversation/revision").asText();
        change(HttpMethod.PATCH, path(conversation), Map.of("approvalPolicy", "full_access"), revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals("default", snapshot(conversation).at("/conversation/approvalPolicy").asText());
        execute(run);
        revision = snapshot(conversation).at("/conversation/revision").asText();
        String key = UUID.randomUUID().toString();
        var changed = data(change(HttpMethod.PATCH, path(conversation), Map.of("approvalPolicy", "auto_approve"), revision, key).andExpect(status().isOk()).andReturn());
        assertEquals(changed, data(change(HttpMethod.PATCH, path(conversation), Map.of("approvalPolicy", "auto_approve"), revision, key).andExpect(status().isOk()).andReturn()));
        assertEquals("auto_approve", snapshot(conversation).at("/conversation/approvalPolicy").asText());
        change(HttpMethod.PATCH, path(conversation), Map.of("approvalPolicy", "full_access"), revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals("default", runs.find(enterprise, run, false).orElseThrow().executionConfig().path("approvalPolicy").asText());
        var next = data(write(path(conversation) + "/messages", input("继续检查"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertEquals("auto_approve", runs.find(enterprise, next.path("runId").asText(), false).orElseThrow().executionConfig().path("approvalPolicy").asText());
    }

    @Test
    void invalidPolicyDoesNotCreateConversationOrTask() throws Exception {
        var request = (ObjectNode) json.valueToTree(body());
        request.put("approvalPolicy", "anything");
        write(base() + "/conversations", request, UUID.randomUUID().toString()).andExpect(status().is(422));
        assertEquals(0, count("agent_conversation"));
        assertEquals(0, count("agent_run"));
    }

    @ParameterizedTest
    @CsvSource({"default,write,true", "auto_approve,write,false", "auto_approve,destructive,true", "full_access,unknown,false"})
    void workflowToolNodesUseTheSameConversationPolicy(String policy, String operationClass, boolean confirmation) throws Exception {
        String pluginVersion = prepareTool(operationClass);
        var graph = json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(
            workflowNode("start", "start", Map.of("inputSchema", Map.of("type", "object"))),
            workflowNode("work", "tool", Map.of("pluginVersionId", pluginVersion, "toolName", "Read_Item", "inputMapping", Map.of("text", "隔离流程测试", "recipient", "policy@example.test", "password", "test-only", "quantity", 1))),
            workflowNode("end", "end", Map.of("outputMapping", Map.of("result", "${steps.work.output}")))), "edges", List.of(
            Map.of("edgeId", "start-work", "source", "start", "target", "work", "branch", "default"),
            Map.of("edgeId", "work-end", "source", "work", "target", "end", "branch", "default"))));
        String resource = data(write(base() + "/resources", Map.of("kind", "workflow", "name", "审批策略流程", "description", "隔离的工具执行验证", "tagIds", List.of(), "config", graph), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        String workflowVersion = data(change(HttpMethod.POST, base() + "/resources/" + resource + "/publish", Map.of("releaseNote", "验证审批策略"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(value -> {
            value.put("agentType", "workflow").putNull("modelProfileId").put("entryWorkflowVersionId", workflowVersion).put("maxSteps", 50);
            value.remove("temperature");
            value.set("workflowVersionIds", json.valueToTree(List.of(workflowVersion)));
        });
        var request = (ObjectNode) json.valueToTree(body());
        request.put("approvalPolicy", policy);
        var accepted = data(write(base() + "/conversations", request, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        execute(run);
        var record = runs.find(enterprise, run, false).orElseThrow();
        assertEquals(confirmation ? "waiting_approval" : "completed", record.status(), record::toString);
        assertEquals(confirmation ? 0 : 1, executions.get());
        assertEquals(confirmation ? 1 : 0, count("run_approval"));
        assertEquals(callsBefore, modelCalls.get(), "工具节点直接执行，不应额外调用模型");
    }

    private Map<String, Object> workflowNode(String id, String type, Map<String, Object> config) {
        return Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config);
    }

    @Test
    void changingPolicyRequiresRunPermissionAndCannotAccessAnotherOwnersConversation() {
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "conversation-only", "对话管理", "不能执行员工", DataScope.OWN, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("conversation.manage", "conversation.view"));
        var member = EnterpriseTestData.member(users, permissions, enterprise, "policy-" + UUID.randomUUID(), "策略边界测试的独立完整口令", "对话成员", List.of(role));
        var hire = hires.establish(enterprise, member.id(), agent, Instant.now());
        String id = UUID.randomUUID().toString();
        conversations.create(id, enterprise, member.id(), agent, version, hire.id(), "权限验证", "normal", Instant.now());
        var actor = new AuthContext(member, enterprise, Set.copyOf(permissions.listPermissionCodes(member.id(), enterprise)));
        var input = new ConversationUpdateInput(null, null, null, "full_access");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> management.update(actor, id, input, 1)).getStatusCode().value());
        var administrator = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> management.update(administrator, id, input, 1)).getStatusCode().value());
        assertEquals("default", conversations.find(enterprise, member.id(), id, false).orElseThrow().approvalPolicy());
    }

    private String path(String conversation) {
        return base() + "/conversations/" + conversation;
    }

    private JsonNode snapshot(String conversation) throws Exception {
        return data(mvc.perform(get(path(conversation)).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private void execute(String run) throws Exception {
        allowModelCalls = true;
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> {
                var record = runs.find(enterprise, run, false).orElseThrow();
                return record.terminal() || record.status().equals("waiting_approval");
            });
        }
    }

    private String prepareTool(String operationClass) throws Exception {
        REMOTE.reset();
        executions.set(0);
        REMOTE.toolResult = input -> {
            executions.incrementAndGet();
            return json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "操作已完成")), "isError", false));
        };
        ObjectNode config = (ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"mcp","builtinCode":null,"transport":"streamable_http",
             "endpoint":"https://example.test/mcp","credentialId":null,"timeoutSeconds":10,"enabledToolNames":["Read_Item"]}
            """);
        config.put("endpoint", REMOTE.endpoint());
        String id = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "策略测试工具", "description", "仅用于隔离测试", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        change(HttpMethod.POST, base() + "/plugins/" + id + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        String pluginVersion = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "策略测试"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        // 独立测试数据库明确登记类别；生产远程自报提示仍保持未知类别。
        assertEquals(1, databaseAccess.mapper(PluginToolSqlMapper.class).update(new LambdaUpdateWrapper<PluginToolRow>()
            .eq(PluginToolRow::getEnterpriseId, enterprise).eq(PluginToolRow::getPluginVersionId, pluginVersion)
            .eq(PluginToolRow::getName, "Read_Item").set(PluginToolRow::getOperationClass, operationClass)));
        configureAgent(value -> value.set("pluginVersionIds", json.valueToTree(List.of(pluginVersion))));
        var iteration = new AtomicInteger();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            if (iteration.getAndIncrement() == 0) {
                String name = modelTool(request, "Read_Item");
                frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "policy-operation", "type", "function", "function", Map.of("name", name,
                    "arguments", json.writeValueAsString(Map.of("text", "隔离测试", "recipient", "policy@example.test", "password", "test-only", "quantity", 1)))))), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "已根据执行结果完成说明。"), "stop");
            }
        };
        return pluginVersion;
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", "policy-model", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}

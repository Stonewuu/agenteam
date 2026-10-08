package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.execution.ConversationModelService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.ModelProfileFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用隔离数据库和本机模型验证选择保存、权限边界与实际生成参数。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationModelSelectionApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private RunMapper runs;

    @Autowired
    private ConversationMapper conversations;

    @Autowired
    private ConversationModelService modelSelections;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/model-selection-workspace");
        registry.add("execution.state-root", () -> "target/model-selection-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void defaultsAndExplicitSelectionReachTheActualModelEvenWhenDefaultIsDisabled() throws Exception {
        String alternative = reasoningModel(enterprise, "alternative");
        var initialOptions = options(null);
        schemas.validate("ConversationModelOptions", initialOptions);
        String defaultId = initialOptions.at("/defaultSelection/modelProfileId").asText();
        assertTrue(initialOptions.at("/defaultSelection/reasoningEffort").isNull());
        databaseAccess.mapper(ModelProfileSqlMapper.class).update(new LambdaUpdateWrapper<ModelProfileRow>()
            .eq(ModelProfileRow::getEnterpriseId, enterprise).eq(ModelProfileRow::getId, defaultId)
            .set(ModelProfileRow::getEnabled, false));
        assertTrue(data(mvc.perform(get(base() + "/employees/" + agent).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("canRun").asBoolean());
        mvc.perform(get(base() + "/agents/" + agent + "/input-options").param("kind", "skill").cookie(cookie)).andExpect(status().isOk());

        var accepted = create(selection(alternative, "high"));
        String conversation = accepted.path("conversationId").asText();
        var config = runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().executionConfig().path("config");
        assertEquals(alternative, config.path("modelProfileId").asText());
        assertEquals("high", config.path("reasoningEffort").asText());
        assertFalse(config.has("temperature"));
        var captured = new AtomicReference<JsonNode>();
        modelResponse = exchange -> {
            captured.set(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(("data: " + json.writeValueAsString(Map.of("id", "model-selection-response", "choices",
                    List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", "已完成。"), "finish_reason", "stop"))))
                    + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        };
        allowModelCalls = true;
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> "completed".equals(runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().status()));
        }
        assertEquals("alternative-model", captured.get().path("model").asText());
        assertEquals("high", captured.get().path("reasoning_effort").asText());
        assertEquals(8192, captured.get().path("max_completion_tokens").asInt());
        assertFalse(captured.get().has("max_tokens"));
        assertFalse(captured.get().has("temperature"));
        assertEquals(selection(alternative, "high"), snapshot(conversation).at("/conversation/modelSelection"));
        assertEquals(selection(alternative, "high"), options(conversation).path("selection"));
        assertFalse(options(conversation).toString().contains("isolated-execution-secret"));
    }

    @Test
    void savedSelectionSurvivesReloadAndRetryWhileBusyAndStaleWritesAreRejected() throws Exception {
        String alternative = reasoningModel(enterprise, "saved");
        var accepted = create(null);
        String conversation = accepted.path("conversationId").asText();
        String path = path(conversation) + "/model-selection";
        String busyRevision = snapshot(conversation).at("/conversation/revision").asText();
        change(HttpMethod.PUT, path, selection(alternative, "high"), busyRevision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        cancel(accepted);
        String revision = snapshot(conversation).at("/conversation/revision").asText();
        String key = UUID.randomUUID().toString();
        var changed = data(change(HttpMethod.PUT, path, selection(alternative, "low"), revision, key).andExpect(status().isOk()).andReturn());
        schemas.validate("Conversation", changed);
        assertEquals(changed, data(change(HttpMethod.PUT, path, selection(alternative, "low"), revision, key).andExpect(status().isOk()).andReturn()));
        change(HttpMethod.PUT, path, selection(alternative, "high"), revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(selection(alternative, "low"), options(conversation).path("selection"));
        var retried = data(write(base() + "/runs/" + accepted.path("runId").asText() + "/retry", null, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var original = runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().executionConfig().path("config");
        var retry = runs.find(enterprise, retried.path("runId").asText(), false).orElseThrow().executionConfig().path("config");
        assertFalse(original.path("modelProfileId").asText().equals(alternative));
        assertEquals(alternative, retry.path("modelProfileId").asText());
        assertEquals("low", retry.path("reasoningEffort").asText());
        cancel(retried);
        var next = data(write(path(conversation) + "/messages", input("继续"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertEquals("low", runs.find(enterprise, next.path("runId").asText(), false).orElseThrow().executionConfig().at("/config/reasoningEffort").asText());
    }

    @Test
    void agentReasoningDefaultAppliesToLegacyClientsAndNullExplicitlyResetsIt() throws Exception {
        String configured = reasoningModel(enterprise, "default");
        configureAgent(config -> {
            config.put("modelProfileId", configured).put("reasoningEffort", "high");
            config.remove("temperature");
        });
        assertEquals(selection(configured, "high"), options(null).path("defaultSelection"));
        var accepted = create(null);
        String conversation = accepted.path("conversationId").asText();
        assertEquals("high", runs.find(enterprise, accepted.path("runId").asText(), false).orElseThrow().executionConfig().at("/config/reasoningEffort").asText());
        cancel(accepted);
        var row = snapshot(conversation).path("conversation");
        change(HttpMethod.PUT, path(conversation) + "/model-selection", selection(configured, null), row.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        var next = data(write(path(conversation) + "/messages", input("使用模型默认设置"), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertFalse(runs.find(enterprise, next.path("runId").asText(), false).orElseThrow().executionConfig().path("config").has("reasoningEffort"));
        cancel(next);
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getId, conversation)
            .set(AgentConversationRow::getModelProfileId, null).set(AgentConversationRow::getReasoningEffort, null));
        assertEquals(selection(configured, "high"), options(conversation).path("selection"));
    }

    @Test
    void invalidAndForeignSelectionsDoNotCreateMessagesOrUseQuota() throws Exception {
        String alternative = reasoningModel(enterprise, "validation");
        String otherEnterprise = provisioning.create("其他模型测试企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        String foreign = reasoningModel(otherEnterprise, "foreign");
        for (var chosen : List.of(selection(alternative, "xhigh"), selection(alternative, "unknown"), selection("", "low"))) {
            write(base() + "/conversations", body(chosen), UUID.randomUUID().toString()).andExpect(status().is(422));
        }
        write(base() + "/conversations", body(selection(foreign, "high")), UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertFalse(options(null).toString().contains(foreign));
        assertEquals(0, count("agent_conversation"));
        assertEquals(0, count("agent_run"));
        assertEquals(0, reserved());
        var accepted = create(null);
        String conversation = accepted.path("conversationId").asText();
        cancel(accepted);
        var current = snapshot(conversation).path("conversation");
        change(HttpMethod.PUT, "/api/v1/enterprises/" + otherEnterprise + "/conversations/" + conversation + "/model-selection",
            selection(foreign, "low"), current.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertEquals(current.path("modelSelection"), snapshot(conversation).at("/conversation/modelSelection"));
    }

    private String reasoningModel(String scope, String code) {
        return models.saveProfiles(List.of(new ModelProfileFixture(scope, admin, code, 1, "思考模型 " + code,
                "openai", code + "-model", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "EXECUTION_TEST_SECRET",
                new ModelCapabilities(true, false, 8192, 32768, List.of("text"), List.of("low", "high")), true)),
            ignored -> "isolated-execution-secret").getFirst();
    }

    @Test
    void modelChangesRequireRunPermissionAndCannotChangeAnotherUsersConversation() {
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "model-read-only", "对话查看", "没有员工执行权限", DataScope.OWN, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("conversation.view", "conversation.manage"));
        var member = EnterpriseTestData.member(users, permissions, enterprise, "model-" + UUID.randomUUID(),
            "模型权限测试的独立完整口令", "模型查看成员", List.of(role));
        var hire = hires.establish(enterprise, member.id(), agent, Instant.now());
        String id = UUID.randomUUID().toString();
        conversations.create(id, enterprise, member.id(), agent, version, hire.id(), "模型权限检查", "normal", Instant.now());
        var actor = new AuthContext(member, enterprise, Set.copyOf(permissions.listPermissionCodes(member.id(), enterprise)));
        var selected = new ModelSelection(reasoningModel(enterprise, "permission"), "high");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> modelSelections.select(actor, id, selected, 1)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> modelSelections.options(actor, agent, id)).getStatusCode().value());
        var administrator = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> modelSelections.select(administrator, id, selected, 1)).getStatusCode().value());
        assertEquals(1, conversations.find(enterprise, member.id(), id, false).orElseThrow().revision());
    }

    private ObjectNode selection(String model, String effort) {
        return json.createObjectNode().put("modelProfileId", model).put("reasoningEffort", effort);
    }

    private ObjectNode body(JsonNode selection) {
        ObjectNode body = json.valueToTree(body());
        if (selection != null) {
            ((ObjectNode) body.path("input")).set("modelSelection", selection);
        }
        return body;
    }

    private JsonNode create(JsonNode selection) throws Exception {
        return data(write(base() + "/conversations", body(selection), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private String path(String conversation) {
        return base() + "/conversations/" + conversation;
    }

    private JsonNode snapshot(String conversation) throws Exception {
        return data(mvc.perform(get(path(conversation)).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode options(String conversation) throws Exception {
        var request = get(base() + "/agents/" + agent + "/model-options").cookie(cookie);
        if (conversation != null) {
            request.param("conversationId", conversation);
        }
        return data(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private void cancel(JsonNode accepted) throws Exception {
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
    }
}

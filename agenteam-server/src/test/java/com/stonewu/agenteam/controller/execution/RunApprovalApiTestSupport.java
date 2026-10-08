package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.user.entity.UserPreferenceRow;
import com.stonewu.agenteam.service.execution.RunCheckpointService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.export.ExportWorker;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.PluginTestServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提供本组接口测试的数据准备、请求调用和隔离环境。
 */
abstract class RunApprovalApiTestSupport extends ExecutionApiTestSupport {

    static final protected InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    static final protected PluginTestServer REMOTE = new PluginTestServer();

    final protected AtomicInteger writes = new AtomicInteger();

    final protected AtomicReference<JsonNode> received = new AtomicReference<>();

    @MockitoSpyBean
    protected RunCheckpointService checkpoints;

    @Autowired
    protected Environment environment;

    @Autowired
    protected NotificationDeliveryService notifications;

    @Autowired
    protected ExportWorker exportWorker;

    @TempDir
    protected Path processDirectory;

    protected boolean repeatProposal;

    protected int proposalCount = 1;

    @DynamicPropertySource
    static protected void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", REMOTE::origin);
        registry.add("execution.workspace-root", () -> "target/p04-approval-workspace");
        registry.add("execution.state-root", () -> "target/p04-approval-state");
    }

    @AfterAll
    protected void closeEnvironment() throws Exception {
        REMOTE.close();
        ENVIRONMENT.close();
    }

    @AfterEach
    protected void restoreCompletionPreference() {
        databaseAccess.mapper(UserPreferenceMapper.class).update(new LambdaUpdateWrapper<UserPreferenceRow>().eq(UserPreferenceRow::getUserId, (admin)).set(UserPreferenceRow::getTaskCompletionNotifications, true));
    }

    protected String exportCsv(String path, Object body) throws Exception {
        var accepted = data(write(path, body, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertTrue(exportWorker.runNext());
        var job = data(mvc.perform(get(base() + "/jobs/" + accepted.path("id").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals("completed", job.path("status").asText(), job::toString);
        var link = data(mvc.perform(get(base() + "/files/" + job.path("resultFileId").asText() + "/download").cookie(cookie)).andExpect(status().isOk()).andReturn());
        var pending = mvc.perform(get(URI.create(link.path("url").asText())).cookie(cookie)).andReturn();
        return new String(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    protected JsonNode waiting() throws Exception {
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String run = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> state(run).equals("waiting_approval") || terminal(run));
            assertEquals("waiting_approval", state(run), () -> diagnostic(run));
        }
        assertEquals(0, writes.get());
        return accepted;
    }

    protected JsonNode approvals(String run) throws Exception {
        return data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    protected ResultActions decision(JsonNode approval, String value, String key) throws Exception {
        return change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", value, "requestHash", approval.path("requestHash").asText()), approval.path("revision").asText(), key);
    }

    protected String state(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    protected boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(state(run));
    }

    protected String diagnostic(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus, AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("status", fixtureValues.get("status"));
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString();
    }

    protected void plugin() throws Exception {
        plugin(10);
    }

    protected void plugin(int timeout) throws Exception {
        REMOTE.reset();
        writes.set(0);
        received.set(null);
        repeatProposal = false;
        proposalCount = 1;
        REMOTE.toolResult = input -> {
            writes.incrementAndGet();
            received.set(input);
            return json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "目标系统已处理请求")), "isError", false));
        };
        var config = (ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"mcp","builtinCode":null,"transport":"streamable_http",
             "endpoint":"https://example.test/mcp","credentialId":null,"timeoutSeconds":10,"enabledToolNames":["Read_Item"]}
            """);
        config.put("endpoint", REMOTE.endpoint()).put("timeoutSeconds", timeout);
        String id = data(write(base() + "/resources", Map.of("kind", "plugin", "name", "远程记录工具", "description", "处理测试记录", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        change(HttpMethod.POST, base() + "/plugins/" + id + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        String version = data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "发布远程工具"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        configureAgent(value -> value.set("pluginVersionIds", json.valueToTree(List.of(version))));
        resetModel();
    }

    protected void resetModel() {
        var iteration = new AtomicInteger();
        modelResponse = exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            int current = iteration.getAndIncrement();
            if (current == 0 || (repeatProposal && current == 1)) {
                String name = modelTool(request, "Read_Item");
                var tools = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < proposalCount; i++) {
                    tools.add(Map.of("index", i, "id", "fixed-operation-" + current + "-" + i, "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(Map.of("text", "原始固定正文", "recipient", "one@example.test", "password", "original-test-password", "quantity", current == 0 ? json.valueToTree(1) : json.valueToTree(1.0))))));
                }
                frame(exchange, Map.of("tool_calls", tools), "tool_calls");
            } else {
                frame(exchange, Map.of("content", "已根据操作结果完成说明。"), "stop");
            }
        };
    }

    protected void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var chunk = Map.of("id", "approval-model", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}

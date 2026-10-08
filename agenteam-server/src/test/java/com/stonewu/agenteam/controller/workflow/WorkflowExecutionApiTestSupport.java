package com.stonewu.agenteam.controller.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.RunCheckpointMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.PluginTestServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提供本组接口测试的数据准备、请求调用和隔离环境。
 */
abstract class WorkflowExecutionApiTestSupport extends ExecutionApiTestSupport {

    static final protected InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    static final protected AtomicInteger READS = new AtomicInteger();

    static final protected AtomicInteger READ_FAILURES = new AtomicInteger();

    static final protected List<Long> READ_TIMES = new CopyOnWriteArrayList<>();

    static volatile protected int readFailureStatus = 503;

    static final protected HttpServer WEB = web();

    static final protected PluginTestServer REMOTE = new PluginTestServer();

    @MockitoSpyBean
    protected RunCheckpointMapper checkpointRecords;

    @DynamicPropertySource
    static protected void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", () -> origin() + "," + REMOTE.origin());
        registry.add("execution.workspace-root", () -> "target/p06-workflow-execution-workspace");
        registry.add("execution.state-root", () -> "target/p06-workflow-execution-state");
    }

    @AfterAll
    protected void closeEnvironment() throws Exception {
        WEB.stop(0);
        REMOTE.close();
        ENVIRONMENT.close();
    }

    @BeforeEach
    protected void resetReadFailures() {
        READ_FAILURES.set(0);
        READ_TIMES.clear();
        readFailureStatus = 503;
    }

    protected String readPlugin() throws Exception {
        return publish(create("plugin", json.readTree("""
            {"icon":"Box","color":"blue","pluginType":"builtin","builtinCode":"web_read","transport":null,
             "endpoint":null,"credentialId":null,"timeoutSeconds":30,"enabledToolNames":["read_url"]}
            """)));
    }

    protected String remotePlugin(AtomicInteger writes, AtomicReference<JsonNode> received) throws Exception {
        REMOTE.reset();
        REMOTE.toolResult = input -> {
            writes.incrementAndGet();
            received.set(input);
            return json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "目标记录已处理")), "isError", false));
        };
        var config = json.readTree("""
            {"icon":"Box","color":"blue","pluginType":"mcp","builtinCode":null,"transport":"streamable_http",
             "endpoint":"https://example.test/mcp","credentialId":null,"timeoutSeconds":10,"enabledToolNames":["Read_Item"]}
            """);
        ((ObjectNode) config).put("endpoint", REMOTE.endpoint());
        String resource = create("plugin", config);
        change(HttpMethod.POST, base() + "/plugins/" + resource + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        return publish(resource);
    }

    protected void waitForDecision(String run) throws Exception {
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> runStatus(run).equals("waiting_approval") || terminal(run));
        }
        assertEquals("waiting_approval", runStatus(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString());
    }

    protected void decide(JsonNode confirmation, String decision) throws Exception {
        change(HttpMethod.POST, base() + "/approvals/" + confirmation.path("id").asText() + "/decision", Map.of("decision", decision, "requestHash", confirmation.path("requestHash").asText()), confirmation.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
    }

    protected void useWorkflow(JsonNode workflow) throws Exception {
        String workflowVersion = publish(create("workflow", workflow));
        var config = (ObjectNode) json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getResourceId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList()));
        config.put("agentType", "workflow").putNull("modelProfileId").put("entryWorkflowVersionId", workflowVersion).put("maxSteps", 50);
        config.remove("temperature");
        config.set("workflowVersionIds", json.valueToTree(List.of(workflowVersion)));
        agent = create("agent", config);
        version = publish(agent);
        hires.establish(enterprise, admin, agent, Instant.now());
    }

    protected JsonNode submit() throws Exception {
        return data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    protected String runStatus(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    protected boolean terminal(String run) {
        return List.of("completed", "failed", "cancelled").contains(runStatus(run));
    }

    protected void assertCompleted(String run) {
        assertEquals("completed", runStatus(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString());
    }

    protected JsonNode snapshot(JsonNode accepted) throws Exception {
        return data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    protected List<JsonNode> stepList(String run) throws Exception {
        var value = data(mvc.perform(get(base() + "/runs/" + run + "/steps").cookie(cookie).param("limit", "100")).andExpect(status().isOk()).andReturn());
        return json.convertValue(value.path("items"), new TypeReference<List<JsonNode>>() {
        });
    }

    protected JsonNode approvals(String run) throws Exception {
        return data(mvc.perform(get(base() + "/runs/" + run + "/approvals").cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    protected void validateEvents(JsonNode accepted) {
        long sequence = 0;
        for (var event : events.after(enterprise, accepted.path("conversationId").asText(), 0, 1000)) {
            assertEquals(Long.toString(++sequence), event.sequence());
            schemas.validate("ExecutionEvent", json.valueToTree(event));
        }
    }

    protected String create(String kind, JsonNode config) throws Exception {
        return data(write(base() + "/resources", Map.of("kind", kind, "name", "流程执行验收", "description", "真实节点结果", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    protected String publish(String id) throws Exception {
        return data(change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "验证节点执行"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    protected ObjectNode linear(JsonNode work, Map<String, Object> output) {
        return graph(List.of(start(), work, end(output)), List.of(edge("start", "work"), edge("work", "end")));
    }

    protected ObjectNode start() {
        return node("start", "start", Map.of("inputSchema", Map.of("type", "object")));
    }

    protected ObjectNode end(Map<String, Object> output) {
        return node("end", "end", Map.of("outputMapping", output));
    }

    protected ObjectNode transform(String id, String text) {
        return node(id, "transform", Map.of("fields", List.of(Map.of("target", "text", "literal", text))));
    }

    protected ObjectNode node(String id, String type, Object config) {
        return json.valueToTree(Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config));
    }

    protected JsonNode edge(String from, String to) {
        return edge(from, to, "default");
    }

    protected JsonNode edge(String from, String to, String branch) {
        return json.valueToTree(Map.of("edgeId", from + "-" + to, "source", from, "target", to, "branch", branch));
    }

    protected ObjectNode graph(List<JsonNode> nodes, List<JsonNode> edges) {
        return json.valueToTree(Map.of("icon", "GitBranch", "color", "blue", "nodes", nodes, "edges", edges));
    }

    protected void frame(HttpExchange exchange, String text) throws IOException {
        frame(exchange, Map.of("content", text), "stop");
    }

    protected void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var value = Map.of("id", UUID.randomUUID().toString(), "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(value) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    static protected String origin() {
        return "http://127.0.0.1:" + WEB.getAddress().getPort();
    }

    static protected HttpServer web() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                READS.incrementAndGet();
                READ_TIMES.add(System.nanoTime());
                if (READ_FAILURES.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    exchange.sendResponseHeaders(readFailureStatus, -1);
                    exchange.close();
                    return;
                }
                byte[] body = "工作流读取到的实际网页".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("无法启动工作流工具验收服务", error);
        }
    }
}

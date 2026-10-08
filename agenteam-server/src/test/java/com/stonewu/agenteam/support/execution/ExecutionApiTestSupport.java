package com.stonewu.agenteam.support.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.agent.AgentExecutionAdapter;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.execution.ExecutionMessageWriter;
import com.stonewu.agenteam.service.execution.ExecutionTaskFactory;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.support.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 正式执行接口测试共用独立企业、当前员工版本和本机可控模型。
 */
@SpringBootTest(properties = {"execution.worker.enabled=false", "execution.events.worker-enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ExecutionApiTestSupport {

    protected JsonNode modelResultForCall(JsonNode input, String id) throws IOException {
        return json.readTree(modelTextForCall(input, id));
    }

    protected String modelTextForCall(JsonNode input, String id) {
        for (var message : input.path("messages")) {
            if (id.equals(message.path("tool_call_id").asText())) {
                var content = message.path("content");
                if (content.isTextual()) {
                    return content.asText();
                }
                for (var block : content) {
                    if (block.path("text").isTextual()) {
                        return block.path("text").asText();
                    }
                }
            }
        }
        throw new IllegalStateException("模型请求中没有对应的工具结果：" + id);
    }

    protected String modelTool(JsonNode request, String name) {
        for (var tool : request.path("tools")) {
            if (name.equals(tool.at("/function/name").asText())) {
                return name;
            }
        }
        throw new IllegalStateException("测试请求没有提供指定工具：" + name);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected MybatisTestDatabase databaseAccess;

    @Autowired
    protected AuthMapper users;

    @Autowired
    protected PermissionMapper permissions;

    @Autowired
    protected EnterpriseProvisioningService provisioning;

    @Autowired
    protected ModelProfileTestData models;

    @Autowired
    protected AgentHireMapper hires;

    protected final ApiContractAssertions schemas = new ApiContractAssertions();

    @Autowired
    protected ExecutionEventMapper events;

    @Autowired
    protected RunLifecycleService lifecycle;

    @Autowired
    protected AgentExecutionAdapter adapter;

    @Autowired
    protected ExecutionTaskFactory tasks;

    @Autowired
    protected ExecutionMessageWriter messageWriter;

    protected Cookie cookie;

    protected String csrf;

    protected String admin;

    protected String enterprise;

    protected String agent;

    protected String version;

    protected HttpServer modelServer;

    private final ExecutorService modelRequests = Executors.newVirtualThreadPerTaskExecutor();

    protected final AtomicInteger modelCalls = new AtomicInteger();

    protected int callsBefore;

    protected boolean allowModelCalls;

    @FunctionalInterface
    protected interface ModelResponse {

        void send(HttpExchange exchange) throws IOException;
    }

    protected volatile ModelResponse modelResponse = exchange -> {
        exchange.sendResponseHeaders(500, -1);
        exchange.close();
    };

    @BeforeAll
    protected void initialize() throws Exception {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.setExecutor(modelRequests);
        modelServer.createContext("/", exchange -> {
            modelCalls.incrementAndGet();
            modelResponse.send(exchange);
        });
        modelServer.start();
        refreshCsrf();
        var boot = write("/api/v1/auth/bootstrap", Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "execution-submission-admin", "displayName", "执行管理员", "password", "执行提交测试所使用的独立完整口令", "enterpriseName", "执行基础企业", "email", "execution-admin@example.test", "timezone", "Asia/Shanghai"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn();
        admin = data(boot).path("id").asText();
        cookie = boot.getResponse().getCookie("SESSION");
        refreshCsrf();
    }

    @AfterAll
    protected void close() throws Exception {
        modelServer.stop(0);
        modelRequests.shutdownNow();
    }

    @BeforeEach
    protected void resource() throws Exception {
        callsBefore = modelCalls.get();
        allowModelCalls = false;
        modelResponse = exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        };
        enterprise = provisioning.create("执行提交测试企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        String model = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, admin, "execution-model", 1, "执行模型配置", "openai", "test-model", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "EXECUTION_TEST_SECRET", new ModelCapabilities(true, true, 8192, 32768, List.of("text")), true)), key -> "isolated-execution-secret").getFirst();
        ObjectNode config = (ObjectNode) json.readTree("""
            {"icon":"Sparkles","color":"purple","agentType":"chat","businessRole":"资料整理","modelProfileId":null,
             "instructions":"这是仅供执行器读取的内部指令。","temperature":0.7,"maxSteps":20,"timeoutSeconds":120,
             "attachmentsEnabled":false,"welcomeMessage":"","suggestedQuestions":[],"skillVersionIds":[],"pluginVersionIds":[],
             "knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],"entryWorkflowVersionId":null,"historyMessageLimit":20,
             "memoryEnabled":false,"memoryFields":[],"businessTerms":[],"researchSubagentEnabled":false,"publicExamples":[]}
            """);
        config.put("modelProfileId", model);
        var created = data(write(base() + "/resources", Map.of("kind", "agent", "name", "固定版本的员工", "description", "帮助整理资料", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        agent = created.at("/resource/id").asText();
        var published = mvc.perform(request(HttpMethod.POST, base() + "/resources/" + agent + "/publish").cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).header("If-Match", "\"1\"").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("releaseNote", "固定执行配置")))).andExpect(status().isCreated()).andReturn();
        version = data(published).at("/version/id").asText();
        hires.establish(enterprise, admin, agent, Instant.now());
    }

    @AfterEach
    protected void stopUnclaimedTestRuns() {
        if (enterprise != null) {
            lifecycle.stopForUser(enterprise, admin);
        }
        if (!allowModelCalls) {
            assertEquals(callsBefore, modelCalls.get(), "提交请求不能直接发起模型调用");
        }
    }

    protected void await(BooleanSupplier completed) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!completed.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(completed.getAsBoolean(), "未在限定时间内保存预期执行结果");
    }

    protected Map<String, Object> body() {
        return Map.of("agentId", agent, "input", input("请整理这次活动的准备事项。"));
    }

    protected Map<String, Object> input(String text) {
        return Map.of("text", text, "attachmentIds", List.of(), "skillVersionIds", List.of(), "knowledgeReferences", List.of(), "links", List.of());
    }

    protected int count(String table) {
        return TestDatabaseCounts.enterprise(databaseAccess, table, enterprise);
    }

    protected int reserved() {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).executionApiTestSupportReservedObject(enterprise));
    }

    protected String base() {
        return "/api/v1/enterprises/" + enterprise;
    }

    protected String resourceRevision() {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getRevision).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
    }

    protected ResultActions preview(Object input, String key) throws Exception {
        return change(HttpMethod.POST, base() + "/agents/" + agent + "/preview", input, resourceRevision(), key);
    }

    protected void configureAgent(Consumer<ObjectNode> change) throws Exception {
        var config = (ObjectNode) json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getResourceId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList()));
        change.accept(config);
        change(HttpMethod.PUT, base() + "/resources/" + agent + "/draft", Map.of("name", "固定版本的员工", "description", "帮助整理资料", "tagIds", List.of(), "config", config), resourceRevision(), UUID.randomUUID().toString()).andExpect(status().isOk());
        var published = data(change(HttpMethod.POST, base() + "/resources/" + agent + "/publish", Map.of("releaseNote", "更新执行测试配置"), resourceRevision(), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        version = published.at("/version/id").asText();
    }

    protected JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    protected void refreshCsrf() throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        if (result.getResponse().getCookie("SESSION") != null) {
            cookie = result.getResponse().getCookie("SESSION");
        }
        csrf = data(result).path("token").asText();
    }

    protected ResultActions write(String path, Object body, String key) throws Exception {
        return change(HttpMethod.POST, path, body, null, key);
    }

    protected ResultActions change(HttpMethod method, String path, Object body, String revision, String key) throws Exception {
        var value = request(method, path).cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
        if (revision != null) {
            value.header("If-Match", "\"" + revision + "\"");
        }
        if (body != null) {
            value.content(json.writeValueAsString(body));
        }
        return mvc.perform(value);
    }
    protected List<MvcResult> concurrent(String firstKey, String secondKey) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submitTogether(firstKey, ready, start));
            var second = executor.submit(() -> submitTogether(secondKey, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    private MvcResult submitTogether(String key, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return write(base() + "/conversations", body(), key).andReturn();
    }
}

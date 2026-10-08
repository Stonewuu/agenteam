package com.stonewu.agenteam.journey;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaEntryFixtureMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.file.FileInspectionWorker;
import com.stonewu.agenteam.service.knowledge.KnowledgeProcessingWorker;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.stonewu.agenteam.support.FileScanTestServer;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.PluginTestServer;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 完整流程使用真实业务接口、受控模型和只写入本次测试记录的远程工具。
 */
public abstract class PlatformJourneyFixture extends ExecutionApiTestSupport {

    protected static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    protected static final FileScanTestServer SCANNER = new FileScanTestServer();

    protected static final PluginTestServer REMOTE = new PluginTestServer();

    protected static final Path ROOT = directory();

    protected static final String SOURCE_TEXT = "活动报销需要保留原始凭证，并由负责人员核对金额。";

    protected final AtomicInteger remoteWrites = new AtomicInteger();

    private final AtomicReference<Throwable> modelFailure = new AtomicReference<>();

    @Autowired
    private FileInspectionWorker inspector;

    @Autowired
    private KnowledgeProcessingWorker knowledgeWorker;

    @Autowired
    private ScheduleTriggerService triggers;

    @Autowired
    private ScheduleActionWorker actionWorker;

    protected String sourceFile;

    protected String sourceDocument;

    public record Journey(String conversation, String run, String citation, String todo, String schedule,
                          String scheduledRun) {
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("files.root", () -> ROOT.resolve("files").toString());
        registry.add("execution.workspace-root", () -> ROOT.resolve("workspace").toString());
        registry.add("execution.state-root", () -> ROOT.resolve("state").toString());
        registry.add("files.scan.host", () -> "127.0.0.1");
        registry.add("files.scan.port", SCANNER::port);
        registry.add("network.allowed-private-origins", REMOTE::origin);
    }

    @AfterAll
    void closeJourneyDependencies() throws Exception {
        REMOTE.close();
        SCANNER.close();
        ENVIRONMENT.close();
    }

    protected Journey completeJourney() throws Exception {
        prepareCapabilities();
        JsonNode accepted = submitForApproval();
        String conversation = accepted.path("conversationId").asText(), run = accepted.path("runId").asText();
        approveAndFinish(run);
        assertEquals(1, remoteWrites.get());
        var snapshot = read(base() + "/conversations/" + conversation);
        schemas.validate("ConversationSnapshot", snapshot);
        assertTrue(snapshot.toString().contains("凭证已登记，待办需要核对原件。"));
        String citation = null;
        for (var block : snapshot.at("/messages/1/blocks")) {
            if (block.path("type").asText().equals("citation")) {
                citation = block.at("/citation/chunkId").asText();
            }
        }
        assertTrue(citation != null && !citation.isBlank());
        var original = read(base() + "/knowledge/citations/" + citation);
        assertEquals(sourceFile, original.path("fileId").asText());
        assertEquals(SOURCE_TEXT, original.path("excerpt").asText());
        var todo = new LinkedHashMap<String, Object>();
        todo.put("title", "核对活动原始凭证");
        todo.put("description", "按本次对话结果核对原件");
        todo.put("ownerUserId", admin);
        todo.put("teamId", null);
        todo.put("dueDate", null);
        todo.put("priority", "normal");
        todo.put("sourceType", "message");
        todo.put("sourceConversationId", conversation);
        todo.put("sourceMessageId", snapshot.at("/messages/1/id").asText());
        todo.put("sourceRunId", null);
        String todoId = data(write(base() + "/todos", todo, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        assertEquals(conversation, read(base() + "/todos/" + todoId).path("sourceConversationId").asText());
        Instant due = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(300);
        var plan = new LinkedHashMap<String, Object>();
        plan.put("name", "每日核对活动凭证");
        plan.put("hireId", hires.forAgent(enterprise, admin, agent, false).orElseThrow().id());
        plan.put("agentVersionId", version);
        plan.put("inputText", "再次核对报销规定并登记结果");
        plan.put("frequency", "daily");
        plan.put("localDate", null);
        plan.put("localTime", due.atZone(ZoneOffset.UTC).toLocalTime().toString());
        plan.put("weekdays", List.of());
        plan.put("monthDay", null);
        plan.put("timezone", "UTC");
        plan.put("enabled", true);
        plan.put("maxRetries", 0);
        String schedule = data(write(base() + "/schedules", plan, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("id").asText();
        // 模拟五分钟前到期且尚未检查的计划，实际发生记录与执行仍由正式调度服务创建。
        databaseAccess.mapper(ScheduleSqlMapper.class).update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, (enterprise)).eq(ScheduledTaskRow::getId, (schedule)).set(ScheduledTaskRow::getNextRunAt, (Timestamp.from(due))).set(ScheduledTaskRow::getLastCheckedAt, (Timestamp.from(due.minusSeconds(10)))));
        configureModel();
        triggers.process(new ScheduleMapper.Candidate(enterprise, admin, schedule));
        assertTrue(actionWorker.runOnce());
        String scheduledRun = read(base() + "/schedules/" + schedule).at("/latestOccurrence/runId").asText();
        assertFalse(scheduledRun.isBlank());
        waitForApproval(scheduledRun);
        approveAndFinish(scheduledRun);
        assertEquals(2, remoteWrites.get());
        assertEquals("completed", read(base() + "/schedules/" + schedule).at("/latestOccurrence/status").asText());
        assertEquals(2, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaEntryFixtureMapper.class).platformJourneyFixtureCompleteJourneyObject(enterprise)));
        return new Journey(conversation, run, citation, todoId, schedule, scheduledRun);
    }

    protected JsonNode submitForApproval() throws Exception {
        configureModel();
        allowModelCalls = true;
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("核对报销资料并登记凭证")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        waitForApproval(accepted.path("runId").asText());
        return accepted;
    }

    private void waitForApproval(String run) throws Exception {
        int before = remoteWrites.get();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> List.of("waiting_approval", "completed", "failed").contains(runStatus(run)));
        }
        assertEquals("waiting_approval", runStatus(run), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus, AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (run))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("status", fixtureValues.get("status"));
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()) + "；本机模型错误：" + modelFailure.get());
        assertEquals(before, remoteWrites.get(), "确认前不能写入远程记录");
    }

    protected void approveAndFinish(String run) throws Exception {
        var approval = read(base() + "/runs/" + run + "/approvals").get(0);
        change(HttpMethod.POST, base() + "/approvals/" + approval.path("id").asText() + "/decision", Map.of("decision", "approve", "requestHash", approval.path("requestHash").asText()), approval.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> List.of("completed", "failed").contains(runStatus(run)));
        }
        assertEquals("completed", runStatus(run));
    }

    protected JsonNode read(String path) throws Exception {
        return data(mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    protected String runStatus(String run) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    private void prepareCapabilities() throws Exception {
        SCANNER.reset();
        REMOTE.reset();
        remoteWrites.set(0);
        REMOTE.tools = json.valueToTree(List.of(Map.of("name", "write_note", "description", "登记本次验收凭证", "inputSchema", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text")), "annotations", Map.of("readOnlyHint", false))));
        REMOTE.toolResult = input -> {
            assertEquals("已经核对凭证", input.path("text").asText());
            remoteWrites.incrementAndGet();
            return json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "凭证登记成功")), "isError", false));
        };
        var pluginConfig = (ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"mcp","builtinCode":null,"transport":"streamable_http",
             "endpoint":"https://example.test/mcp","credentialId":null,"timeoutSeconds":10,"enabledToolNames":["write_note"]}
            """);
        pluginConfig.put("endpoint", REMOTE.endpoint());
        String plugin = create("plugin", "活动凭证登记", pluginConfig);
        change(HttpMethod.POST, base() + "/plugins/" + plugin + "/check", null, "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        String pluginVersion = publish(plugin);
        String knowledge = create("knowledge", "活动报销规定", Map.of("icon", "BookOpen", "color", "blue", "description", "实际测试文件", "retrievalMode", "keyword", "maxResults", 8, "maxContextCharacters", 8000));
        byte[] bytes = SOURCE_TEXT.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        sourceFile = data(write(base() + "/files", Map.of("purpose", "knowledge", "resourceId", knowledge, "name", "活动报销规定.txt", "sizeBytes", bytes.length, "mediaType", "text/plain", "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).path("fileId").asText();
        mvc.perform(request(HttpMethod.PUT, base() + "/files/" + sourceFile + "/content").cookie(cookie).header("Origin", "http://localhost:3000").header("X-CSRF-Token", csrf).contentType("text/plain").content(bytes)).andExpect(status().isNoContent());
        write(base() + "/files/" + sourceFile + "/complete", Map.of("sizeBytes", bytes.length, "sha256", hash), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertTrue(inspector.runNext());
        sourceDocument = data(write(base() + "/knowledge/" + knowledge + "/documents", Map.of("fileIds", List.of(sourceFile)), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn()).get(0).path("id").asText();
        assertTrue(knowledgeWorker.runNext());
        String knowledgeVersion = publish(knowledge);
        var config = (ObjectNode) json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getResourceId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList()));
        config.set("pluginVersionIds", json.valueToTree(List.of(pluginVersion)));
        config.set("knowledgeVersionIds", json.valueToTree(List.of(knowledgeVersion)));
        agent = create("agent", "活动事务员工", config);
        version = publish(agent);
        write(base() + "/hires", Map.of("agentId", agent, "note", "完成活动报销核对"), UUID.randomUUID().toString()).andExpect(status().isOk());
    }

    private String create(String kind, String name, Object config) throws Exception {
        return data(write(base() + "/resources", Map.of("kind", kind, "name", name, "description", "完整流程验收", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private String publish(String resource) throws Exception {
        var publication = new LinkedHashMap<String, Object>();
        publication.put("releaseNote", "完整流程固定版本");
        if (resource.equals(agent)) {
            publication.put("listing", Map.of("listed", true, "hirePolicy", "automatic"));
        }
        return data(change(HttpMethod.POST, base() + "/resources/" + resource + "/publish", publication, "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    private void configureModel() {
        var iteration = new AtomicInteger();
        modelFailure.set(null);
        modelResponse = exchange -> {
            try {
                var input = json.readTree(exchange.getRequestBody());
                int current = iteration.getAndIncrement();
                if (current < 2) {
                    if (current == 1) {
                        assertTrue(input.toString().contains(SOURCE_TEXT));
                    }
                    String needed = current == 0 ? "knowledge_search" : "write_note", alias = null;
                    for (var tool : input.path("tools")) {
                        boolean matches = current == 0 ? tool.at("/function/name").asText().startsWith("platform_knowledge_search_") : tool.at("/function/description").asText().contains("登记本次验收凭证");
                        if (matches) {
                            alias = tool.at("/function/name").asText();
                        }
                    }
                    if (alias == null) {
                        throw new IOException("模型没有收到本次需要的工具：" + needed + "；收到：" + input.path("tools"));
                    }
                    var arguments = current == 0 ? Map.of("query", "报销") : Map.of("text", "已经核对凭证");
                    frame(exchange, Map.of("tool_calls", List.of(Map.of("index", 0, "id", "journey-tool-" + current, "type", "function", "function", Map.of("name", alias, "arguments", json.writeValueAsString(arguments))))), "tool_calls");
                } else {
                    assertTrue(input.toString().contains("凭证登记成功"));
                    frame(exchange, Map.of("content", "凭证已登记，待办需要核对原件。"), "stop");
                }
            } catch (Exception | AssertionError failure) {
                modelFailure.compareAndSet(null, failure);
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        };
    }

    private void frame(HttpExchange exchange, Map<String, Object> delta, String finish) throws IOException {
        var chunk = Map.of("id", "journey-model", "object", "chat.completion.chunk", "created", 1, "model", "test-model", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private static Path directory() {
        try {
            return Files.createTempDirectory(Path.of("target").toAbsolutePath(), "platform-journey-");
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建独立流程验收目录", failure);
        }
    }
}

package com.stonewu.agenteam.controller.memory;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.memory.MemorySqlMapper;
import com.stonewu.agenteam.mapper.test.http.ApiRequestFixtureMapper;
import com.stonewu.agenteam.mapper.user.UserPreferenceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.memory.entity.AgentMemoryRow;
import com.stonewu.agenteam.model.memory.request.MemoryWriteRequest;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.user.entity.UserPreferenceRow;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import com.stonewu.agenteam.service.execution.RunWorker;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.memory.MemoryRetentionService;
import com.stonewu.agenteam.service.memory.MemoryService;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证用户主动保存、当前正文与实际模型请求，不使用假记忆服务代替执行接入。
 */
@Import(SharedEnterpriseTestEdition.class)
class MemoryApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private MemoryService memories;

    @Autowired
    private MemoryRetentionService retention;

    @Autowired
    private PreviewRetentionService previews;

    @Autowired
    private ResourceGrantService grants;

    @Autowired
    private PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @BeforeEach
    void enabledEmployee() throws Exception {
        configureAgent(config -> {
            config.put("memoryEnabled", true);
            config.put("icon", "NotebookPen").put("color", "mint");
            config.set("memoryFields", json.valueToTree(topics()));
        });
        databaseAccess.mapper(UserPreferenceMapper.class).update(new LambdaUpdateWrapper<UserPreferenceRow>().eq(UserPreferenceRow::getUserId, (admin)).set(UserPreferenceRow::getMemoryEnabled, true));
    }

    @Test
    void explicitSaveRequiresBothSwitchesAllowedTopicsAndNonSensitiveContent() throws Exception {
        preference(false);
        write(path(), value("语言", "请使用简体中文"), key()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMORY_DISABLED"));
        preference(true);
        write(path(), value("未允许的主题", "简短回答"), key()).andExpect(status().isUnprocessableEntity());
        String sensitive = "API key 是 sk-memory-fixture-secret-only";
        var response = write(path(), value("语言", sensitive), key()).andExpect(status().isUnprocessableEntity()).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains(sensitive));
        write(path(), value("语言", "同事的病历需要长期保存"), key()).andExpect(status().isUnprocessableEntity());
        configureAgent(config -> config.put("memoryEnabled", false));
        write(path(), value("语言", "请使用简体中文"), key()).andExpect(status().isConflict());
        assertEquals(0, count("agent_memory"));
    }

    @Test
    void repeatedWritesOnlyStoreReferencesAndDeletedContentCannotBeReadFromOldRequests() throws Exception {
        String createKey = key();
        var input = value("语言", "偏好正文甲只保存在本人记忆表");
        var created = data(write(path(), input, createKey).andExpect(status().isCreated()).andReturn());
        schemas.validate("Memory", created);
        assertEquals(created, data(write(path(), input, createKey).andExpect(status().isCreated()).andReturn()));
        String id = created.path("id").asText(), updateKey = key();
        var next = value("语言", "偏好正文乙只能由本人查看");
        var updated = data(change(HttpMethod.PUT, path() + "/" + id, next, "1", updateKey).andExpect(status().isOk()).andReturn());
        assertEquals(updated, data(change(HttpMethod.PUT, path() + "/" + id, next, "1", updateKey).andExpect(status().isOk()).andReturn()));
        assertEquals(updated, data(write(path(), input, createKey).andExpect(status().isCreated()).andReturn()));
        String stored = String.join("", databaseAccess.mapper(ApiRequestFixtureMapper.class).memoryApiRepeatedWritesOnlyStoreReferencesAndDeletedContentCannotBeReadFromOldRequestsList(enterprise));
        assertFalse(stored.contains(input.content()));
        assertFalse(stored.contains(next.content()));
        assertTrue(stored.contains(id));
        String deleteKey = key();
        change(HttpMethod.DELETE, path() + "/" + id, null, "2", deleteKey).andExpect(status().isOk());
        change(HttpMethod.DELETE, path() + "/" + id, null, "2", deleteKey).andExpect(status().isOk());
        write(path(), input, createKey).andExpect(status().isNotFound());
        change(HttpMethod.PUT, path() + "/" + id, next, "1", updateKey).andExpect(status().isNotFound());
        assertEquals(0, count("agent_memory"));
    }

    @Test
    void administratorCannotReadAnotherPersonsPreferencesAndOwnersCanClearAfterLosingEmployeeAccess() throws Exception {
        var member = member();
        share();
        var saved = memories.create(member, agent, value("语言", "仅属于测试成员的偏好"));
        assertTrue(memories.list(actor(admin), agent, null, 30).items().isEmpty());
        assertThrows(ResponseStatusException.class, () -> memories.update(actor(admin), agent, saved.id(), value("语言", "不能修改别人的偏好"), 1));
        assertThrows(ResponseStatusException.class, () -> memories.delete(actor(admin), agent, saved.id(), 1));
        var another = provisioning.create("偏好隔离企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        assertThrows(ResponseStatusException.class, () -> memories.list(new AuthContext(users.findById(admin).orElseThrow(), another, Set.copyOf(permissions.listPermissionCodes(admin, another))), agent, null, 30));
        grants.replace(actor(admin), agent, Long.parseLong(resourceRevision()), List.of());
        assertFalse(memories.context(member, agent, null).canSave());
        assertEquals("NotebookPen", memories.context(member, agent, null).agentIcon());
        assertEquals("mint", memories.context(member, agent, null).agentColor());
        assertEquals(1, memories.agents(member, null, null, 30).items().getFirst().memoryCount());
        assertEquals("NotebookPen", memories.agents(member, null, null, 30).items().getFirst().agentIcon());
        assertEquals("mint", memories.agents(member, null, null, 30).items().getFirst().agentColor());
        memories.update(member, agent, saved.id(), value("语言", "停用后仍由本人修改"), 1);
        memories.clear(member, agent);
        assertEquals(0, count("agent_memory"));
    }

    @Test
    void sourceUsesItsFixedConfigurationAndExpiredPreviewOnlyRemovesTheSourceReference() throws Exception {
        var accepted = data(preview(Map.of("input", input("用户确认偏好的来源")), key()).andExpect(status().isAccepted()).andReturn());
        String message = accepted.path("inputMessageId").asText(), conversation = accepted.path("conversationId").asText();
        configureAgent(config -> config.set("memoryFields", json.valueToTree(List.of("语言"))));
        var context = data(mvc.perform(get(path() + "/context").param("sourceMessageId", message).cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("MemoryContext", context);
        assertTrue(context.path("allowedTopics").toString().contains("格式"));
        write(path(), value("格式", "先给结论"), key()).andExpect(status().isUnprocessableEntity());
        var source = new MemoryWriteRequest("格式", "请先给结论，再列依据", message);
        var saved = data(write(path(), source, key()).andExpect(status().isCreated()).andReturn());
        var member = member();
        share();
        assertThrows(ResponseStatusException.class, () -> memories.create(member, agent, source));
        lifecycle.stopForUser(enterprise, admin);
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)).set(AgentConversationRow::getCreatedAt, (Timestamp.from(Instant.now().minusSeconds(8 * 86400)))));
        previews.clean();
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(MemorySqlMapper.class).selectList(new LambdaQueryWrapper<AgentMemoryRow>().select(AgentMemoryRow::getSourceMessageId).eq(AgentMemoryRow::getId, (saved.path("id").asText()))).stream().map(fixtureRecord -> fixtureRecord.getSourceMessageId()).toList()));
        assertEquals(source.content(), memories.get(actor(admin), agent, saved.path("id").asText()).content());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentMessageRow>().eq(AgentMessageRow::getId, (message)))));
    }

    @Test
    void expiredPreferencesAreNotListedAndTheirTopicsCanBeSavedAgain() throws Exception {
        var saved = memories.create(actor(admin), agent, value("语言", "即将过期的偏好"));
        databaseAccess.mapper(MemorySqlMapper.class).update(new LambdaUpdateWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getId, (saved.id())).set(AgentMemoryRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        assertTrue(memories.list(actor(admin), agent, null, 30).items().isEmpty());
        assertTrue(memories.agents(actor(admin), null, null, 30).items().isEmpty());
        var replacement = memories.create(actor(admin), agent, value("语言", "重新确认的新偏好"));
        assertFalse(saved.id().equals(replacement.id()));
        assertEquals(1, count("agent_memory"));
        assertTrue(Instant.parse(replacement.expiresAt()).isAfter(Instant.now().plusSeconds(179 * 86400)));
        databaseAccess.mapper(MemorySqlMapper.class).update(new LambdaUpdateWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getId, (replacement.id())).set(AgentMemoryRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        retention.clean();
        retention.clean();
        assertEquals(0, count("agent_memory"));
    }

    @Test
    void concurrentNewTopicsCannotExceedTwentyAndRollbackDoesNotRetainPreferenceContent() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            memories.create(actor(admin), agent, value("语言", "回滚内容"));
            transaction.setRollbackOnly();
        });
        assertEquals(0, count("agent_memory"));
        for (String topic : topics().subList(0, 19)) {
            memories.create(actor(admin), agent, value(topic, "已确认的工作偏好"));
        }
        var changed = new ArrayList<>(topics());
        changed.remove("主题3");
        changed.add("新增主题");
        configureAgent(config -> config.set("memoryFields", json.valueToTree(changed)));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> limited(start, "主题20"));
            var second = pool.submit(() -> limited(start, "新增主题"));
            start.countDown();
            assertEquals(1, first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(20, count("agent_memory"));
        var page = memories.list(actor(admin), agent, null, 5);
        assertTrue(page.hasMore());
        assertEquals(5, page.items().size());
        assertEquals(5, memories.list(actor(admin), agent, page.nextCursor(), 5).items().size());
        var member = member();
        assertThrows(ApiException.class, () -> memories.list(member, agent, page.nextCursor(), 5));
    }

    @Test
    void realModelRequestsDropDeletedDisabledAndExpiredPreferencesWhileTheConversationKeepsItsFixedVersion() throws Exception {
        var requests = model();
        share();
        var member = member();
        memories.create(member, agent, value("语言", "其他人的标记不应送入本人的模型请求"));
        var language = memories.create(actor(admin), agent, value("语言", "请在开头使用语言偏好甲标记"));
        var format = memories.create(actor(admin), agent, value("格式", "请按照格式偏好乙组织段落"));
        var first = data(write(base() + "/conversations", body(), key()).andExpect(status().isAccepted()).andReturn());
        run(first);
        assertTrue(requests.getFirst().toString().contains("语言偏好甲"));
        assertTrue(requests.getFirst().toString().contains("格式偏好乙"));
        assertFalse(requests.getFirst().toString().contains("其他人的标记"));
        String conversation = first.path("conversationId").asText();
        configureAgent(config -> config.put("memoryEnabled", false));
        memories.delete(actor(admin), agent, language.id(), 1);
        run(continueConversation(conversation, "继续处理第二项"));
        assertFalse(requests.get(1).toString().contains("语言偏好甲"));
        assertTrue(requests.get(1).toString().contains("格式偏好乙"));
        preference(false);
        run(continueConversation(conversation, "继续处理第三项"));
        assertFalse(requests.get(2).toString().contains("格式偏好乙"));
        preference(true);
        databaseAccess.mapper(MemorySqlMapper.class).update(new LambdaUpdateWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getId, (format.id())).set(AgentMemoryRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        run(continueConversation(conversation, "继续处理第四项"));
        assertFalse(requests.get(3).toString().contains("格式偏好乙"));
        run(data(write(base() + "/conversations", body(), key()).andExpect(status().isAccepted()).andReturn()));
        assertFalse(requests.get(4).toString().contains("偏好乙"));
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(8, snapshot.path("messages").size());
        assertFalse(snapshot.toString().contains("语言偏好甲"));
        assertEquals(5, requests.size());
    }

    @Test
    void workflowModelNodeUsesTheReferencedEmployeeIdentityForPreferences() throws Exception {
        var requests = model();
        memories.create(actor(admin), agent, value("语言", "工作流节点使用的本人偏好标记"));
        var nodes = List.of(node("start", "start", Map.of("inputSchema", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))))), node("work", "agent", Map.of("agentVersionId", version, "inputMapping", Map.of("text", "${input.text}"))), node("end", "end", Map.of("outputMapping", Map.of("text", "${steps.work.output.text}"))));
        var graph = Map.of("icon", "GitBranch", "color", "blue", "nodes", nodes, "edges", List.of(edge("start", "work"), edge("work", "end")));
        String workflow = data(write(base() + "/resources", Map.of("kind", "workflow", "name", "偏好节点验收", "description", "独立节点读取本人偏好", "tagIds", List.of(), "config", graph), key()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        var accepted = data(change(HttpMethod.POST, base() + "/workflows/" + workflow + "/preview", Map.of("draft", graph, "input", Map.of("text", "工作流的实际输入")), "1", key()).andExpect(status().isAccepted()).andReturn());
        run(accepted);
        assertEquals(1, requests.size());
        assertTrue(requests.getFirst().toString().contains("工作流节点使用的本人偏好标记"));
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getAgentVersionId).eq(AgentRunRow::getId, (accepted.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getAgentVersionId()).toList()));
    }

    @Test
    void closingThePersonalSwitchAndSavingConcurrentlyLeaveNoWayToAddAfterTheSwitchIsClosed() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var save = pool.submit(() -> {
                start.await();
                try {
                    memories.create(actor(admin), agent, value("语言", "同时提交的偏好"));
                } catch (ApiException disabled) {
                    assertEquals("MEMORY_DISABLED", disabled.code());
                }
                return true;
            });
            var close = pool.submit(() -> {
                start.await();
                preference(false);
                return true;
            });
            start.countDown();
            assertTrue(save.get(15, TimeUnit.SECONDS));
            assertTrue(close.get(15, TimeUnit.SECONDS));
        }
        assertFalse(memories.context(actor(admin), agent, null).canSave());
        assertEquals("MEMORY_DISABLED", assertThrows(ApiException.class, () -> memories.create(actor(admin), agent, value("格式", "关闭后不得新增"))).code());
        assertTrue(count("agent_memory") <= 1);
    }

    @Test
    void concurrentUpdateAndDeletionCannotBothChangeTheSameRevision() throws Exception {
        var value = memories.create(actor(admin), agent, value("语言", "并发处理前的偏好"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var edit = pool.submit(() -> changing(start, () -> memories.update(actor(admin), agent, value.id(), value("语言", "并发处理后的偏好"), 1)));
            var delete = pool.submit(() -> changing(start, () -> memories.delete(actor(admin), agent, value.id(), 1)));
            start.countDown();
            assertEquals(1, edit.get(15, TimeUnit.SECONDS) + delete.get(15, TimeUnit.SECONDS));
        }
        var remaining = memories.list(actor(admin), agent, null, 30).items();
        if (!remaining.isEmpty()) {
            assertEquals("2", remaining.getFirst().revision());
            assertEquals("并发处理后的偏好", remaining.getFirst().content());
        }
    }

    private List<JsonNode> model() {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        allowModelCalls = true;
        modelResponse = exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var response = Map.of("id", key(), "choices", List.of(Map.of("index", 0, "delta", Map.of("content", "本次工作已经处理。"), "finish_reason", "stop")));
            exchange.getResponseBody().write(("data: " + json.writeValueAsString(response) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        };
        return requests;
    }

    private void run(JsonNode accepted) throws Exception {
        String id = accepted.path("runId").asText();
        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            await(() -> List.of("completed", "failed", "cancelled").contains(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList())));
        }
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()), () -> DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectMaps(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getErrorCode, AgentRunRow::getErrorMessage).eq(AgentRunRow::getId, (id))).stream().map(fixtureValues -> {
            Map<String, Object> fixtureRow = new LinkedCaseInsensitiveMap<>();
            fixtureRow.put("error_code", fixtureValues.get("error_code"));
            fixtureRow.put("error_message", fixtureValues.get("error_message"));
            return fixtureRow;
        }).toList()).toString());
    }

    private JsonNode continueConversation(String conversation, String text) throws Exception {
        return data(write(base() + "/conversations/" + conversation + "/messages", input(text), key()).andExpect(status().isAccepted()).andReturn());
    }

    private List<String> topics() {
        var topics = new ArrayList<>(List.of("语言", "格式"));
        for (int index = 3; index <= 20; index++) {
            topics.add("主题" + index);
        }
        return topics;
    }

    private AuthContext actor(String user) {
        return new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
    }

    private AuthContext member() {
        var user = EnterpriseTestData.member(users, permissions, enterprise, "memory_" + key().substring(0, 8), "memory-test-password-2026", "记忆测试成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        databaseAccess.mapper(UserPreferenceMapper.class).update(new LambdaUpdateWrapper<UserPreferenceRow>().eq(UserPreferenceRow::getUserId, (user.id())).set(UserPreferenceRow::getMemoryEnabled, true));
        hires.establish(enterprise, user.id(), agent, Instant.now());
        return actor(user.id());
    }

    private void share() {
        grants.replace(actor(admin), agent, Long.parseLong(resourceRevision()), List.of(new ResourceGrantSpec("enterprise", enterprise, "use")));
    }

    private void preference(boolean enabled) throws Exception {
        String revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(UserPreferenceMapper.class).selectList(new LambdaQueryWrapper<UserPreferenceRow>().select(UserPreferenceRow::getRevision).eq(UserPreferenceRow::getUserId, (admin))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        change(HttpMethod.PATCH, "/api/v1/me/preferences", Map.of("memoryEnabled", enabled), revision, key()).andExpect(status().isOk());
    }

    private int limited(CountDownLatch start, String topic) throws InterruptedException {
        start.await();
        try {
            memories.create(actor(admin), agent, value(topic, "新确认的偏好"));
            return 1;
        } catch (ApiException limit) {
            assertEquals("MEMORY_LIMIT", limit.code());
            return 0;
        }
    }

    private int changing(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return 1;
        } catch (ApiException conflict) {
            assertTrue(Set.of("VERSION_CONFLICT", "RESOURCE_NOT_FOUND").contains(conflict.code()));
            return 0;
        }
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        return Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config);
    }

    private Map<String, Object> edge(String from, String to) {
        return Map.of("edgeId", from + "-" + to, "source", from, "target", to, "branch", "default");
    }

    private String path() {
        return base() + "/agents/" + agent + "/memories";
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private MemoryWriteRequest value(String topic, String content) {
        return new MemoryWriteRequest(topic, content, null);
    }
}

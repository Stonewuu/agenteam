package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.mapper.usage.QuotaEntryTableMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.*;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.RunStepView;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.model.usage.entity.QuotaEntryRow;
import com.stonewu.agenteam.service.agent.ExecutionPaths;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.execution.ExecutionEventPublisher;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.ModelProfileFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证会话管理、固定预览和执行详情，不使用模拟业务结果。
 */
@Import(SharedEnterpriseTestEdition.class)
class ConversationManagementApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private ConversationQueryService queries;

    @Autowired
    private RunStepMapper steps;

    @Autowired
    private RunMapper runs;

    @Autowired
    private PreviewRetentionService previewRetention;

    @Autowired
    private ExecutionPaths executionPaths;

    @Autowired
    private ExecutionEventPublisher publisher;

    @Autowired
    private ExecutionEventCache eventCache;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("execution.workspace-root", () -> "target/p03-management-workspace");
        registry.add("execution.state-root", () -> "target/p03-management-state");
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void activeRunBlocksArchiveAndDeleteWhileRenamingFavoriteAndRevisionRemainConsistent() throws Exception {
        var versions = databaseAccess.mapper(ResourceVersionSqlMapper.class);
        var published = versions.selectOne(new LambdaQueryWrapper<ResourceVersionRow>()
            .eq(ResourceVersionRow::getEnterpriseId, enterprise).eq(ResourceVersionRow::getId, version));
        var appearance = (ObjectNode) json.readTree(published.getConfigJson());
        appearance.put("icon", "Feather").put("color", "mint");
        assertEquals(1, versions.update(null, new LambdaUpdateWrapper<ResourceVersionRow>()
            .eq(ResourceVersionRow::getEnterpriseId, enterprise).eq(ResourceVersionRow::getId, version)
            .set(ResourceVersionRow::getConfigJson, json.writeValueAsString(appearance))));
        var accepted = submit();
        String conversation = accepted.path("conversationId").asText();
        var before = snapshot(conversation).path("conversation");
        assertEquals("Feather", before.path("agentIcon").asText());
        assertEquals("mint", before.path("agentColor").asText());
        String revision = before.path("revision").asText();
        change(HttpMethod.PATCH, path(conversation), Map.of("status", "archived"), revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        change(HttpMethod.DELETE, path(conversation), null, revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        String key = UUID.randomUUID().toString();
        var input = Map.of("title", "会议准备", "favorite", true);
        var renamed = data(change(HttpMethod.PATCH, path(conversation), input, revision, key).andExpect(status().isOk()).andReturn());
        schemas.validate("Conversation", renamed);
        assertEquals(renamed, data(change(HttpMethod.PATCH, path(conversation), input, revision, key).andExpect(status().isOk()).andReturn()));
        change(HttpMethod.PATCH, path(conversation), Map.of("title", "过期编辑"), revision, UUID.randomUUID().toString()).andExpect(status().isConflict());
        var favorites = data(mvc.perform(get(base() + "/conversations").cookie(cookie).param("favorite", "true").param("query", "会议")).andExpect(status().isOk()).andReturn());
        assertEquals(conversation, favorites.at("/items/0/id").asText());
        assertEquals("Feather", favorites.at("/items/0/agentIcon").asText());
        assertEquals("mint", favorites.at("/items/0/agentColor").asText());
        cancel(accepted);
        var archived = data(change(HttpMethod.PATCH, path(conversation), Map.of("status", "archived"), snapshot(conversation).at("/conversation/revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertFalse(archived.path("canContinue").asBoolean());
        write(path(conversation) + "/messages", input("归档后不能发送"), UUID.randomUUID().toString()).andExpect(status().isConflict());
    }

    @Test
    void deleteKeepsMessagesAndRestoreHonorsThirtyDayLimit() throws Exception {
        var accepted = submit();
        cancel(accepted);
        String conversation = accepted.path("conversationId").asText();
        String key = UUID.randomUUID().toString(), revision = snapshot(conversation).at("/conversation/revision").asText();
        change(HttpMethod.DELETE, path(conversation), null, revision, key).andExpect(status().isOk());
        change(HttpMethod.DELETE, path(conversation), null, revision, key).andExpect(status().isOk());
        mvc.perform(get(path(conversation)).cookie(cookie)).andExpect(status().isNotFound());
        var deleted = data(mvc.perform(get(base() + "/conversations").cookie(cookie).param("status", "deleted")).andExpect(status().isOk()).andReturn()).at("/items/0");
        assertEquals(conversation, deleted.path("id").asText());
        assertEquals(2, count("agent_message"));
        var restored = data(change(HttpMethod.POST, path(conversation) + "/restore", null, deleted.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertEquals("active", restored.path("status").asText());
        change(HttpMethod.DELETE, path(conversation), null, restored.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)).set(AgentConversationRow::getDeletedAt, (Timestamp.from(Instant.now().minusSeconds(31 * 86400)))));
        String latest = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ConversationSqlMapper.class).selectList(new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getRevision).eq(AgentConversationRow::getId, (conversation))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        change(HttpMethod.POST, path(conversation) + "/restore", null, latest, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertTrue(data(mvc.perform(get(base() + "/conversations").cookie(cookie).param("status", "deleted")).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
    }

    @Test
    void feedbackIsPrivateRepeatableAndCanBeWithdrawn() throws Exception {
        var accepted = submit();
        String conversation = accepted.path("conversationId").asText();
        String path = base() + "/messages/" + accepted.path("outputMessageId").asText() + "/feedback";
        change(HttpMethod.PUT, path, Map.of("value", "positive"), null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        cancel(accepted);
        change(HttpMethod.PUT, base() + "/messages/" + accepted.path("inputMessageId").asText() + "/feedback", Map.of("value", "positive"), null, UUID.randomUUID().toString()).andExpect(status().isConflict());
        String key = UUID.randomUUID().toString();
        change(HttpMethod.PUT, path, Map.of("value", "positive"), null, key).andExpect(status().isOk());
        change(HttpMethod.PUT, path, Map.of("value", "positive"), null, key).andExpect(status().isOk());
        assertEquals(1, count("message_feedback"));
        var snapshot = snapshot(conversation);
        schemas.validate("ConversationSnapshot", snapshot);
        assertEquals("positive", snapshot.at("/messages/1/feedback").asText());
        var other = EnterpriseTestData.member(users, permissions, enterprise, "other-" + UUID.randomUUID(), "其他管理员的独立测试口令", "另一管理员", List.of(permissions.builtinRoleId(enterprise, "enterprise-admin")));
        var actor = new AuthContext(other, enterprise, Set.copyOf(permissions.listPermissionCodes(other.id(), enterprise)));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> queries.snapshot(actor, conversation)).getStatusCode().value());
        var withdrawn = json.createObjectNode().putNull("value");
        change(HttpMethod.PUT, path, withdrawn, null, UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(0, count("message_feedback"));
        assertTrue(snapshot(conversation).at("/messages/1/feedback").isNull());
    }

    @Test
    void previewSnapshotsUnsavedDraftAndReservesTwoIndependentRunsWithoutChangingResource() throws Exception {
        var resource = data(mvc.perform(get(base() + "/resources/" + agent).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertTrue(resource.at("/resource/allowedActions").toString().contains("preview"));
        var original = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getResourceId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList()));
        var config = (ObjectNode) original.deepCopy();
        config.put("instructions", "只在本次预览中使用的指令");
        var input = Map.of("input", input("比较两种回答"), "draft", Map.of("name", "临时员工名称", "description", "仅用于预览", "tagIds", List.of(), "config", config));
        String key = UUID.randomUUID().toString();
        write(base() + "/agents/" + agent + "/preview", input, UUID.randomUUID().toString()).andExpect(status().isPreconditionRequired());
        var first = data(preview(input, key).andExpect(status().isAccepted()).andReturn());
        assertEquals(first, data(preview(input, key).andExpect(status().isAccepted()).andReturn()));
        String model = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, admin, "preview-alternative", 1, "对比模型配置", "openai", "alternative-model", "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1", "EXECUTION_TEST_SECRET", new ModelCapabilities(true, true, 8192, 32768, List.of("text")), true)), name -> "isolated-preview-secret").getFirst();
        var alternative = json.valueToTree(input);
        ((ObjectNode) alternative).put("modelProfileId", model);
        var second = data(preview(alternative, UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        assertFalse(first.path("runId").equals(second.path("runId")));
        assertEquals(2, reserved());
        assertEquals(original, json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getResourceId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList())));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, (agent)))));
        var fixed = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getExecutionConfigJson).eq(AgentRunRow::getId, (first.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getExecutionConfigJson()).toList()));
        assertEquals("只在本次预览中使用的指令", fixed.at("/config/instructions").asText());
        assertEquals("临时员工名称", fixed.path("name").asText());
        var secondConfig = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getExecutionConfigJson).eq(AgentRunRow::getId, (second.path("runId").asText()))).stream().map(fixtureRecord -> fixtureRecord.getExecutionConfigJson()).toList()));
        assertEquals(model, secondConfig.at("/config/modelProfileId").asText());
        assertEquals(original.path("modelProfileId"), fixed.at("/config/modelProfileId"));
        assertTrue(fixed.path("agentVersionId").isNull());
        write(path(first.path("conversationId").asText()) + "/messages", input("不能继续预览对话"), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        cancel(first);
        cancel(second);
        assertEquals(0, reserved());
    }

    @Test
    void messageAndStepPaginationHaveStableOrderAndDoNotAcceptAnotherRunsCursor() throws Exception {
        var accepted = submit();
        String conversation = accepted.path("conversationId").asText(), runId = accepted.path("runId").asText();
        var lease = lifecycle.claim("分页验证执行器").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        var attempt = steps.attemptId(run);
        var firstStep = new RunStepView("step-first", null, attempt, "agent", "处理任务", 1, "running", null, Instant.now().toString(), null, null);
        var secondStep = new RunStepView("step-second", "step-first", attempt, "model", "生成回复", 2, "running", null, Instant.now().toString(), null, null);
        var block = new ContentBlock("visible-result", "text", null, 1, "1", "可查看的结果", "running", null, null, null, null, null, null);
        messageWriter.save(lease, UUID.randomUUID().toString(), List.of(ExecutionChange.step(firstStep), ExecutionChange.step(secondStep), ExecutionChange.replace(block)));
        lifecycle.finish(lease, "completed", null, null);
        var recent = data(mvc.perform(get(path(conversation) + "/messages").cookie(cookie).param("limit", "1")).andExpect(status().isOk()).andReturn());
        schemas.validate("MessageHistory", recent);
        assertEquals(accepted.path("outputMessageId"), recent.at("/messages/0/id"));
        var older = data(mvc.perform(get(path(conversation) + "/messages").cookie(cookie).param("beforeMessageId", recent.path("nextBeforeMessageId").asText()).param("limit", "1")).andExpect(status().isOk()).andReturn());
        schemas.validate("MessageHistory", older);
        assertEquals(accepted.path("inputMessageId"), older.at("/messages/0/id"));
        var attempts = data(mvc.perform(get(base() + "/runs/" + runId + "/attempts").cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("RunAttempt", attempts.get(0));
        assertEquals("completed", attempts.at("/0/status").asText());
        var first = data(mvc.perform(get(base() + "/runs/" + runId + "/steps").cookie(cookie).param("limit", "1")).andExpect(status().isOk()).andReturn());
        schemas.validate("RunStep", first.at("/items/0"));
        assertEquals("step-first", first.at("/items/0/id").asText());
        var second = data(mvc.perform(get(base() + "/runs/" + runId + "/steps").cookie(cookie).param("cursor", first.path("nextCursor").asText()).param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertEquals("step-second", second.at("/items/0/id").asText());
        assertEquals("step-first", second.at("/items/0/parentStepId").asText());
        var other = submit();
        mvc.perform(get(base() + "/runs/" + other.path("runId").asText() + "/steps").cookie(cookie).param("cursor", first.path("nextCursor").asText())).andExpect(status().isBadRequest());
    }

    @Test
    void previewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversations() throws Exception {
        var normal = submit();
        cancel(normal);
        var accepted = data(preview(Map.of("input", input("预览的私有测试内容")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String conversation = accepted.path("conversationId").asText(), id = accepted.path("runId").asText();
        var previewSnapshot = data(mvc.perform(get(base() + "/conversations/" + conversation).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals("Sparkles", previewSnapshot.at("/conversation/agentIcon").asText());
        assertEquals("purple", previewSnapshot.at("/conversation/agentColor").asText());
        var list = data(mvc.perform(get(base() + "/conversations").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, list.path("items").size());
        assertEquals(normal.path("conversationId"), list.at("/items/0/id"));
        var lease = lifecycle.claim("预览结果清理验证").orElseThrow();
        var run = lifecycle.start(lease).orElseThrow();
        lifecycle.beforeExternalStep(lease);
        String step = UUID.randomUUID().toString(), attempt = steps.attemptId(run);
        var root = new RunStepView(step, null, attempt, "agent", "预览任务", 1, "running", null, Instant.now().toString(), null, null);
        var child = new RunStepView(UUID.randomUUID().toString(), step, attempt, "model", "生成回复", 2, "running", null, Instant.now().toString(), null, null);
        messageWriter.save(lease, UUID.randomUUID().toString(), List.of(ExecutionChange.step(root), ExecutionChange.step(child)));
        lifecycle.finish(lease, "completed", null, null);
        var finished = runs.find(enterprise, id, false).orElseThrow();
        var workspaceFile = Files.createDirectories(executionPaths.workspace(finished)).resolve("preview.txt");
        var stateFile = Files.createDirectories(executionPaths.state(finished)).resolve("state.json");
        Files.writeString(workspaceFile, "预览工作内容");
        Files.writeString(stateFile, "预览状态内容");
        publisher.publish(enterprise, conversation);
        assertTrue(eventCache.last(enterprise, conversation).sequence() > 0);
        var retained = data(preview(Map.of("input", input("保留七天内的新预览")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        cancel(retained);
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (conversation)).set(AgentConversationRow::getCreatedAt, (Timestamp.from(Instant.now().minusSeconds(8 * 86400)))));
        mvc.perform(get(path(conversation)).cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get(path(conversation) + "/events").cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/runs/" + id).cookie(cookie)).andExpect(status().isNotFound());
        previewRetention.clean();
        previewRetention.clean();
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentRunRow>().eq(AgentRunRow::getId, (id)))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ExecutionMessageSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentMessageRow>().eq(AgentMessageRow::getConversationId, (conversation)))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunStepSqlMapper.class).selectCount(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getRunId, (id)))));
        assertEquals(0, eventCache.last(enterprise, conversation).sequence());
        assertFalse(Files.exists(workspaceFile));
        assertFalse(Files.exists(stateFile));
        assertTrue(Math.toIntExact(databaseAccess.mapper(QuotaEntryTableMapper.class).selectCount(new LambdaQueryWrapper<QuotaEntryRow>().eq(QuotaEntryRow::getRunId, (id)).eq(QuotaEntryRow::getState, "consumed"))) > 0);
        assertTrue(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).conversationManagementApiPreviewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversationsObject(enterprise)) > 0);
        snapshot(normal.path("conversationId").asText());
        snapshot(retained.path("conversationId").asText());
    }

    private JsonNode submit() throws Exception {
        return data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }

    private JsonNode snapshot(String id) throws Exception {
        return data(mvc.perform(get(path(id)).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private String path(String conversation) {
        return base() + "/conversations/" + conversation;
    }

    private void cancel(JsonNode accepted) throws Exception {
        write(base() + "/runs/" + accepted.path("runId").asText() + "/cancel", null, UUID.randomUUID().toString()).andExpect(status().isAccepted());
    }
}

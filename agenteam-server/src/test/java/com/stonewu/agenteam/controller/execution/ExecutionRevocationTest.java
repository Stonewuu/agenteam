package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.test.usage.QuotaBucketFixtureMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.NewConversationInput;
import com.stonewu.agenteam.model.execution.request.PreviewInput;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.enterprise.MemberDefinitionService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用实际停用和撤权事务验证运行任务结束、排队释放及私有预览的独立性。
 */
@Import(SharedEnterpriseTestEdition.class)
class ExecutionRevocationTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private RunSubmissionService submissions;

    @Autowired
    private MemberDefinitionService members;

    @Autowired
    private ResourceGrantService grants;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void pausingHireStopsItsRunningTaskAndKeepsSavedTextButDoesNotStopDraftPreview() throws Exception {
        var accepted = submit();
        String run = accepted.path("runId").asText();
        var lease = lifecycle.claim("暂停雇佣验证").orElseThrow();
        lifecycle.start(lease).orElseThrow();
        lifecycle.beforeExternalStep(lease);
        var block = new ContentBlock("partial-output", "text", null, 1, "1", "已经保存的部分内容", "running", null, null, null, null, null, null);
        messageWriter.save(lease, UUID.randomUUID().toString(), List.of(ExecutionChange.replace(block)));
        var preview = data(preview(Map.of("input", input("检查草稿")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        var hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow();
        change(HttpMethod.PATCH, base() + "/hires/" + hire.id(), Map.of("status", "paused"), Long.toString(hire.revision()), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("cancelling", state(run));
        assertEquals("queued", state(preview.path("runId").asText()));
        lifecycle.finish(lease, "completed", null, null);
        assertEquals("cancelled", state(run));
        var snapshot = data(mvc.perform(get(base() + "/conversations/" + accepted.path("conversationId").asText()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals("已经保存的部分内容", snapshot.at("/messages/1/content").asText());
        assertTrue(snapshot.path("activeRun").isNull());
        assertFalse(snapshot.at("/conversation/canContinue").asBoolean());
        assertEquals(1, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(QuotaBucketFixtureMapper.class).conversationManagementApiPreviewsStayOutOfTheNormalListAndExpireWithoutDeletingUsageOrOtherConversationsObject(enterprise)));
    }

    @Test
    void resourceDisabledCountsAndStopsBothQueuedNormalAndPreviewRunsInOneTransaction() throws Exception {
        var normal = submit();
        var preview = data(preview(Map.of("input", input("检查草稿")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String path = base() + "/resources/" + agent;
        var impact = data(mvc.perform(get(path + "/impact").cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ResourceImpact", impact);
        assertEquals(2, impact.path("activeRunCount").asInt());
        assertFalse(impact.path("canDelete").asBoolean());
        change(HttpMethod.PATCH, path + "/status", Map.of("status", "disabled"), impact.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("cancelled", state(normal.path("runId").asText()));
        assertEquals("cancelled", state(preview.path("runId").asText()));
        assertEquals(0, reserved());
        var after = data(mvc.perform(get(path + "/impact").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(0, after.path("activeRunCount").asInt());
        assertTrue(after.path("canDelete").asBoolean());
    }

    @Test
    void revokingFixedVersionDoesNotInvalidateIndependentDraftPreview() throws Exception {
        var normal = submit();
        var preview = data(preview(Map.of("input", input("独立预览")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        change(HttpMethod.POST, base() + "/resources/" + agent + "/versions/" + version + "/revoke", Map.of("reason", "停止此版本"), revision(), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals("cancelled", state(normal.path("runId").asText()));
        assertEquals("queued", state(preview.path("runId").asText()));
        assertEquals(1, reserved());
    }

    @Test
    void permissionRevocationAndMemberDisableImmediatelyReleaseUnstartedTasks() throws Exception {
        var actor = actor(admin);
        var colleague = EnterpriseTestData.member(users, permissions, enterprise, "runner-" + UUID.randomUUID(), "成员独立执行的测试口令", "执行成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var assignment = List.of(new ResourceGrantSpec("enterprise", enterprise, "use"));
        grants.replace(actor, agent, Long.parseLong(revision()), assignment);
        hires.establish(enterprise, colleague.id(), agent, Instant.now());
        var input = new MessageInput("需要停止的任务", List.of(), List.of(), List.of(), List.of());
        var first = submissions.create(actor(colleague.id()), new NewConversationInput(agent, input));
        grants.replace(actor, agent, Long.parseLong(revision()), List.of());
        assertEquals("cancelled", state(first.runId()));
        assertEquals(0, reserved());
        grants.replace(actor, agent, Long.parseLong(revision()), assignment);
        var second = submissions.create(actor(colleague.id()), new NewConversationInput(agent, input));
        long memberRevision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(IdentityQueryMapper.class).selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().select(EnterpriseMemberRow::getRevision).eq(EnterpriseMemberRow::getEnterpriseId, (enterprise)).eq(EnterpriseMemberRow::getUserId, (colleague.id()))).stream().map(fixtureRecord -> fixtureRecord.getRevision()).toList());
        members.status(actor, colleague.id(), "disabled", memberRevision);
        assertEquals("cancelled", state(second.runId()));
        assertEquals(0, reserved());
    }

    @Test
    void previewMaintainerCanStopOwnPreviewWithoutNormalExecutionPermission() {
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "preview-only", "仅预览", "验证预览独立权限", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("agent.preview", "conversation.view"));
        var maintainer = EnterpriseTestData.member(users, permissions, enterprise, "preview-" + UUID.randomUUID(), "仅预览维护者的测试口令", "预览维护者", List.of(role));
        grants.replace(actor(admin), agent, Long.parseLong(revision()), List.of(new ResourceGrantSpec("user", maintainer.id(), "edit")));
        var actor = actor(maintainer.id());
        assertFalse(actor.permissions().contains("agent.run"));
        var accepted = submissions.preview(actor, agent, new PreviewInput(new MessageInput("预览任务", List.of(), List.of(), List.of(), List.of()), null, null));
        assertEquals("cancelled", lifecycle.cancel(actor, accepted.runId()).status());
        assertEquals(0, reserved());
    }

    private AuthContext actor(String user) {
        return new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
    }

    private String state(String id) {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
    }

    private String revision() {
        return DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getRevision).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
    }

    private JsonNode submit() throws Exception {
        return data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
    }
}

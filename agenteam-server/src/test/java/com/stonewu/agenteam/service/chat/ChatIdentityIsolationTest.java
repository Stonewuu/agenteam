package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证跨应用账号绑定、撤销与冲突，以及同群不同用户、企业和权限之间的身份隔离。 */
class ChatIdentityIsolationTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    @Autowired CollaborationDispatchService dispatch;
    @Autowired RunMapper runs;
    @Autowired ResourceAuthorizationMapper grants;
    @Autowired ChatIdentityService identities;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @AfterEach
    void stopRuns() {
        var tasks = databaseAccess.mapper(CollaborationTaskMapper.class).selectList(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise));
        tasks.stream().map(CollaborationTaskRow::getInitiatorUserId).distinct().forEach(user -> lifecycle.stopForUser(enterprise, user));
        databaseAccess.mapper(CollaborationWorkItemMapper.class).update(new LambdaUpdateWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise).eq(CollaborationWorkItemRow::getStatus, "ready")
            .set(CollaborationWorkItemRow::getStatus, "cancelled"));
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void platformAdministratorStartsOwnBoundTaskAndKeepsOwnExecutionIdentity() {
        assertTrue(users.findById(admin).orElseThrow().superAdmin());
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getId, binding.getId())
            .set(UserChannelBindingRow::getUserId, admin));
        assertEquals(admin, identities.actor(enterprise, admin, binding.getId()).userId());
        var denied = receive("administrator-without-agent-access", "管理员也需要智能体使用权限");
        assertThrows(ApiException.class, () -> routing.route(enterprise, denied.getId()));
        assertEquals(0, taskCount());
        grants.replaceGrants(enterprise, agent, initiator.id(), Set.of(new ResourceGrantSpec("user", admin, "use")), Instant.now());
        hires.establish(enterprise, admin, agent, Instant.now());
        var incoming = receive("administrator-own-message", "平台管理员以自己的已绑定账号发起任务");
        routing.route(enterprise, incoming.getId());
        assertEquals(admin, onlyTask().getInitiatorUserId());
        assertEquals(admin, messages().selectById(incoming.getId()).getSenderUserId());
        var accepted = dispatch.submit(dispatch.claim("administrator-own-run").orElseThrow());
        assertEquals(admin, runs.find(enterprise, accepted.runId(), false).orElseThrow().userId());
    }

    @Test
    void platformAdministratorStillNeedsEnabledBindingAndActiveEnterpriseMembership() {
        assertTrue(identities.bindingNotice(enterprise, bot.getConnectionId(), "feishu_open_id", "ou_unknown", null, "feishu").contains("授权并绑定"));
        assertEquals("CHANNEL_BINDING_REQUIRED", assertThrows(ApiException.class,
            () -> identities.actor(enterprise, admin, "missing-binding")).code());
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getId, binding.getId())
            .set(UserChannelBindingRow::getUserId, admin).set(UserChannelBindingRow::getChatEnabled, true));
        assertEquals(admin, identities.actor(enterprise, admin, binding.getId()).userId());
        databaseAccess.mapper(UserChannelBindingMapper.class).update(new LambdaUpdateWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getId, binding.getId())
            .set(UserChannelBindingRow::getChatEnabled, false));
        assertEquals("CHANNEL_BINDING_REQUIRED", assertThrows(ApiException.class,
            () -> identities.actor(enterprise, admin, binding.getId())).code());
        String notice = identities.bindingNotice(enterprise, bot.getConnectionId(), "feishu_open_id", "ou_user", null, "feishu");
        assertTrue(notice.contains("已绑定"));
        assertTrue(notice.contains("个人设置 → 企业账号绑定"));
        assertFalse(notice.contains("重新绑定"));
        String otherEnterprise = provisioning.create("管理员非有效成员的隔离企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        databaseAccess.mapper(IdentityQueryMapper.class).update(new LambdaUpdateWrapper<EnterpriseMemberRow>()
            .eq(EnterpriseMemberRow::getEnterpriseId, otherEnterprise).eq(EnterpriseMemberRow::getUserId, admin)
            .set(EnterpriseMemberRow::getStatus, "disabled"));
        assertFalse(users.isActiveMember(admin, otherEnterprise));
        assertEquals("CHANNEL_BINDING_REQUIRED", assertThrows(ApiException.class,
            () -> identities.actor(otherEnterprise, admin)).code());
    }

    @Test
    void identicalTextWithDifferentPlatformMessageIdsCreatesSeparateRequests() {
        var first = receive("same-text-first", "新任务：统计同样的测试内容");
        var second = receive("same-text-second", "新任务：统计同样的测试内容");
        routing.route(enterprise, first.getId());
        routing.route(enterprise, second.getId());
        assertEquals(2, taskCount());
        assertNotEquals(messages().selectById(first.getId()).getTaskId(), messages().selectById(second.getId()).getTaskId());
    }

    @Test
    void concurrentSameNameUsersHaveIndependentIdentityConversationAndQuota() throws Exception {
        var other = EnterpriseTestData.member(users, permissions, enterprise, "other_" + UUID.randomUUID().toString().substring(0, 8),
            "isolated-other-user-password", initiator.displayName(), List.of(permissions.builtinRoleId(enterprise, "member")));
        grants.replaceGrants(enterprise, agent, admin, Set.of(new ResourceGrantSpec("user", other.id(), "use")), Instant.now());
        hires.establish(enterprise, other.id(), agent, Instant.now());
        var account = new UserChannelBindingRow();
        account.setId(UUID.randomUUID().toString());
        account.setEnterpriseId(enterprise);
        account.setConnectionId(bot.getConnectionId());
        account.setUserId(other.id());
        account.setExternalSubjectType("feishu_open_id");
        account.setExternalSubjectId("ou_other_user");
        account.setChatEnabled(true);
        account.setAuthorizedAt(Instant.now());
        databaseAccess.mapper(UserChannelBindingMapper.class).insert(account);
        var mine = receive("original-user", "相同任务文字");
        var event = message("other-user", "other-user", "user", "相同任务文字");
        event.withObject("/event/sender/sender_id").put("open_id", "ou_other_user");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        var theirs = messages().selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, enterprise)
            .eq(ChatMessageRow::getPlatformMessageId, "other-user"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> routing.route(enterprise, mine.getId()));
            var two = executor.submit(() -> routing.route(enterprise, theirs.getId()));
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }
        var first = dispatch.submit(dispatch.claim("isolation-first").orElseThrow());
        var second = dispatch.submit(dispatch.claim("isolation-second").orElseThrow());
        assertEquals(2, taskCount());
        assertNotEquals(first.conversationId(), second.conversationId());
        var one = runs.find(enterprise, first.runId(), false).orElseThrow();
        var two = runs.find(enterprise, second.runId(), false).orElseThrow();
        assertEquals(Set.of(initiator.id(), other.id()), Set.of(one.userId(), two.userId()));
        assertNotEquals(one.executionConfig().path("workspaceProjectId"), two.executionConfig().path("workspaceProjectId"));
        assertEquals(2, reserved());
        assertEquals(initiator.id(), messages().selectById(mine.getId()).getSenderUserId());
        assertEquals(other.id(), messages().selectById(theirs.getId()).getSenderUserId());
    }

    @Test
    void publishingAnotherVersionChangesNewTasksButNotTheExistingTask() {
        var first = receive("before-publish", "新任务：按第一版处理");
        routing.route(enterprise, first.getId());
        String oldTask = onlyTask().getId();
        var latest = databaseAccess.mapper(ResourceVersionSqlMapper.class).selectById(version);
        latest.setId(UUID.randomUUID().toString());
        latest.setVersionNo(2);
        latest.setReleaseNote("隔离测试的第二版");
        databaseAccess.mapper(ResourceVersionSqlMapper.class).insert(latest);
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getId, agent)
            .set(ResourceRow::getPublishedVersionId, latest.getId()));
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, bot.getId())
            .set(ChatBotBindingRow::getVersionPolicy, "latest_on_new_task").set(ChatBotBindingRow::getPinnedVersionId, null));
        var second = receive("after-publish", "新任务：按第二版处理");
        routing.route(enterprise, second.getId());
        var roles = databaseAccess.mapper(CollaborationParticipantMapper.class).selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, enterprise));
        assertEquals(version, roles.stream().filter(role -> role.getTaskId().equals(oldTask)).findFirst().orElseThrow().getAgentVersionId());
        assertEquals(latest.getId(), roles.stream().filter(role -> !role.getTaskId().equals(oldTask)).findFirst().orElseThrow().getAgentVersionId());
        var accepted = dispatch.submit(dispatch.claim("old-version-still-fixed").orElseThrow());
        assertEquals(version, runs.find(enterprise, accepted.runId(), false).orElseThrow().agentVersionId());
    }

    @Test
    void explicitlyRequestedFinanceWithoutAHireCannotUseAdministratorPrivileges() {
        var finance = secondBot();
        var resource = databaseAccess.mapper(ResourceSqlMapper.class).selectById(agent);
        resource.setId(UUID.randomUUID().toString());
        resource.setName("未雇佣的财务");
        resource.setOwnerUserId(admin);
        resource.setPublishedVersionId(null);
        databaseAccess.mapper(ResourceSqlMapper.class).insert(resource);
        var fixed = databaseAccess.mapper(ResourceVersionSqlMapper.class).selectById(version);
        fixed.setId(UUID.randomUUID().toString());
        fixed.setResourceId(resource.getId());
        databaseAccess.mapper(ResourceVersionSqlMapper.class).insert(fixed);
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getId, resource.getId())
            .set(ResourceRow::getPublishedVersionId, fixed.getId()));
        databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>().eq(ChatBotBindingRow::getId, finance.getId())
            .set(ChatBotBindingRow::getAgentId, resource.getId()).set(ChatBotBindingRow::getPinnedVersionId, fixed.getId()));
        var event = message("unhired-finance", "unhired-finance", "user", "请人事和财务处理工资");
        ((ArrayNode) event.at("/event/message/mentions")).addObject().put("name", "财务").putObject("id").put("open_id", finance.getPlatformBotId());
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        assertThrows(ApiException.class, () -> routing.route(enterprise, onlyMessage().getId()));
        assertEquals(0, taskCount());
        assertEquals(0, reserved());
    }

    @Test
    void oneVerifiedIdentityStartsTaskThroughAnotherApplication() {
        var other = secondBot();
        bindings().update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, binding.getId())
            .set(UserChannelBindingRow::getConnectionId, other.getConnectionId())
            .set(UserChannelBindingRow::getExternalSubjectId, "ou_user_in_other_app").set(UserChannelBindingRow::getExternalUnionId, "on_shared"));
        var event = message("shared-identity", "shared-identity", "user", "请处理一次绑定后的任务");
        event.withObject("/event/sender/sender_id").put("union_id", "on_shared");
        ingress.accept(enterprise, bot.getId(), 1L, event);
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        assertEquals(initiator.id(), onlyTask().getInitiatorUserId());
        assertEquals(binding.getId(), onlyTask().getOriginUserBindingId());
        assertEquals("routed", messages().selectById(onlyMessage().getId()).getRoutingStatus());
        bindings().update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, binding.getId())
            .set(UserChannelBindingRow::getStatus, "revoked").set(UserChannelBindingRow::getRevokedAt, Instant.now()));
        assertThrows(ApiException.class, () -> taskAccess.actor(onlyTask()));
    }

    @Test
    void sharedIdentityRequiresMatchingTypeTenantConnectionAndEnterprise() {
        var other = secondBot();
        assertTrue(identities.find(enterprise, other.getConnectionId(), "feishu_open_id", "ou_user", null).isEmpty());
        shareBinding();
        assertTrue(identities.find(enterprise, other.getConnectionId(), "feishu_open_id", "ou_user", "on_other").isEmpty());
        assertTrue(identities.find(enterprise, other.getConnectionId(), "wecom_userid", "ou_user", "on_shared").isEmpty());
        assertTrue(identities.find(enterprise, other.getConnectionId(), "feishu_open_id", "ou_other", "on_shared").isPresent());
        applications().update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, other.getConnectionId())
            .set(EnterpriseIntegrationRow::getExternalTenantId, "another_tenant"));
        assertTrue(identities.find(enterprise, other.getConnectionId(), "feishu_open_id", "ou_other", "on_shared").isEmpty());
        assertTrue(identities.find("another_enterprise", bot.getConnectionId(), "feishu_open_id", "ou_user", "on_shared").isEmpty());
        applications().update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, bot.getConnectionId())
            .set(EnterpriseIntegrationRow::getStatus, "disabled"));
        assertTrue(identities.find(enterprise, bot.getConnectionId(), "feishu_open_id", "ou_user", "on_shared").isEmpty());
    }

    @Test
    void conflictingOwnersAreRejectedRatherThanChoosingOne() {
        shareBinding();
        var other = secondBot();
        var duplicate = bindings().selectById(binding.getId());
        duplicate.setId(UUID.randomUUID().toString());
        duplicate.setConnectionId(other.getConnectionId());
        duplicate.setUserId(admin);
        bindings().insert(duplicate);
        assertEquals("CHANNEL_IDENTITY_CONFLICT", assertThrows(ApiException.class, () ->
            identities.find(enterprise, other.getConnectionId(), "feishu_open_id", "ou_user", "on_shared")).code());
    }

    @Test
    void personalSettingsShowOneAccountAndRevokeAllMatchingBindings() throws Exception {
        shareBinding();
        bindings().update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, binding.getId()).set(UserChannelBindingRow::getUserId, admin));
        var other = secondBot();
        var duplicate = bindings().selectById(binding.getId());
        duplicate.setId(UUID.randomUUID().toString());
        duplicate.setConnectionId(other.getConnectionId());
        bindings().insert(duplicate);
        String path = base() + "/me/channels";
        var listed = data(mvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, listed.size());
        assertEquals("飞书账号", listed.get(0).path("connectionName").asText());
        String id = listed.get(0).path("id").asText();
        var disabled = data(change(HttpMethod.PATCH, path + "/" + id,
            Map.of("receiveEnabled", false, "externalLoginEnabled", false, "chatEnabled", false),
            listed.get(0).path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertEquals(0, bindings().selectCount(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getChatEnabled, true)));
        change(HttpMethod.DELETE, path + "/" + id, null, disabled.path("revision").asText(), UUID.randomUUID().toString())
            .andExpect(status().isOk());
        assertEquals(0, bindings().selectCount(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).ne(UserChannelBindingRow::getStatus, "revoked")));
    }

    private void shareBinding() {
        bindings().update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, binding.getId())
            .set(UserChannelBindingRow::getExternalUnionId, "on_shared"));
    }

    private UserChannelBindingMapper bindings() {
        return databaseAccess.mapper(UserChannelBindingMapper.class);
    }

    private EnterpriseIntegrationMapper applications() {
        return databaseAccess.mapper(EnterpriseIntegrationMapper.class);
    }
}

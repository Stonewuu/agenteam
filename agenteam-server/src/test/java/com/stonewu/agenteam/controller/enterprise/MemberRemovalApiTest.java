package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTeamTableMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.test.execution.AgentRunFixtureMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseTeamRow;
import com.stonewu.agenteam.model.enterprise.request.MemberRemovalRequest;
import com.stonewu.agenteam.model.enterprise.response.MemberRemovalImpactView;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.request.MessageInput;
import com.stonewu.agenteam.model.execution.request.NewConversationInput;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import com.stonewu.agenteam.model.todo.request.TodoStatusRequest;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.service.enterprise.MemberRemovalQueryService;
import com.stonewu.agenteam.service.enterprise.MemberRemovalService;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.service.resource.ResourceOwnershipService;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.todo.TodoManagementService;
import com.stonewu.agenteam.service.todo.TodoQueryService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 成员移除使用真实会话、计划、待办和所有权记录，私有正文不能成为管理预览。
 */
@Import(SharedEnterpriseTestEdition.class)
class MemberRemovalApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private MemberRemovalQueryService queries;

    @Autowired
    private MemberRemovalService removal;

    @Autowired
    private EnterpriseMapper enterprises;

    @Autowired
    private ResourceOwnershipService ownership;

    @Autowired
    private TodoManagementService todos;

    @Autowired
    private TodoQueryService todoQueries;

    @Autowired
    private RunSubmissionService submissions;

    @Autowired
    private ScheduleManagementService schedules;

    @Autowired
    private NotificationDeliveryService notifications;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private Clock clock;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @AfterEach
    void stopRemainingRuns() {
        reset(clock);
        for (String user : databaseAccess.mapper(AgentRunFixtureMapper.class).memberRemovalApiStopRemainingRunsList(enterprise)) {
            lifecycle.stopForUser(enterprise, user);
        }
    }

    @Test
    void transfersResourcesTeamsAndTodosWhileKeepingPrivateConversationsAndOtherEnterprises() throws Exception {
        var departing = member("待移除的负责人", "enterprise-admin");
        var recipient = member("待办接收人", "builder");
        String other = provisioning.create("保留访问的其他企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        users.addMember(other, departing.userId(), "另一企业成员", Instant.now());
        permissions.replaceUserRoles(departing.userId(), other, Set.of(permissions.builtinRoleId(other, "member")), Instant.now());
        ownership.transfer(actor(admin), agent, departing.userId(), Long.parseLong(resourceRevision()));
        String team = team(departing.userId(), Set.of(departing.userId(), recipient.userId()));
        hires.establish(enterprise, departing.userId(), agent, Instant.now());
        var run = submissions.create(departing, new NewConversationInput(agent, new MessageInput("不能出现在管理预览中的私有会话内容", List.of(), List.of(), List.of(), List.of())));
        var personal = todos.create(departing, new TodoWriteRequest("私有待办的确认摘要", "只交给最终接收人", departing.userId(), null, null, "normal", "message", run.conversationId(), run.inputMessageId(), null));
        todos.create(departing, todo(departing.userId(), team));
        String hire = hires.forAgent(enterprise, departing.userId(), agent, false).orElseThrow().id();
        var schedule = schedules.create(departing, new ScheduleWriteRequest("私有计划", hire, version, "不得返回给移除人的计划输入", "daily", null, "23:59", List.of(), null, "Asia/Shanghai", true, 0));
        long sessionVersion = users.findById(departing.userId()).orElseThrow().sessionVersion();
        var impact = queries.impact(actor(admin), departing.userId());
        schemas.validate("MemberRemovalImpact", json.valueToTree(impact));
        assertEquals(1, impact.resourceCount());
        assertEquals(1, impact.ownedTeamCount());
        assertEquals(2, impact.openTodoCount());
        assertEquals(1, impact.activeRunCount());
        assertEquals(1, impact.enabledScheduleCount());
        String encoded = json.writeValueAsString(impact);
        assertFalse(encoded.contains("私有"));
        assertFalse(encoded.contains(run.conversationId()));
        assertFalse(encoded.contains(personal.id()));
        String requestKey = key();
        var plan = new MemberRemovalRequest(impact.impactToken(), admin, recipient.userId(), false);
        var removed = data(change(HttpMethod.POST, path(departing.userId()) + "/remove", plan, impact.memberRevision(), requestKey).andExpect(status().isOk()).andReturn());
        schemas.validate("Member", removed);
        assertEquals("removed", removed.path("status").asText());
        assertTrue(removed.path("teamIds").isEmpty());
        assertTrue(removed.path("roleIds").isEmpty());
        assertEquals(removed, data(change(HttpMethod.POST, path(departing.userId()) + "/remove", plan, impact.memberRevision(), requestKey).andExpect(status().isOk()).andReturn()));
        assertEquals(admin, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getOwnerUserId).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
        assertEquals(admin, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(EnterpriseTeamTableMapper.class).selectList(new LambdaQueryWrapper<EnterpriseTeamRow>().select(EnterpriseTeamRow::getOwnerUserId).eq(EnterpriseTeamRow::getId, (team))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
        assertEquals(List.of(recipient.userId()), enterprises.findTeam(enterprise, team).orElseThrow().memberUserIds());
        var handed = todoQueries.get(recipient, personal.id());
        assertEquals(recipient.userId(), handed.owner().id());
        assertFalse(handed.sourceAccessible());
        assertNull(handed.sourceConversationId());
        assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (run.runId()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals(0, reserved());
        assertFalse(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getEnabled).eq(ScheduledTaskRow::getId, (schedule.id()))).stream().map(fixtureRecord -> (fixtureRecord.getEnabled() != null && fixtureRecord.getEnabled() != 0)).toList()));
        assertEquals(departing.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getOwnerUserId).eq(ScheduledTaskRow::getId, (schedule.id()))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
        assertEquals(departing.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ConversationSqlMapper.class).selectList(new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getUserId).eq(AgentConversationRow::getId, (run.conversationId()))).stream().map(fixtureRecord -> fixtureRecord.getUserId()).toList()));
        mvc.perform(get(base() + "/conversations/" + run.conversationId()).cookie(cookie)).andExpect(status().isNotFound());
        assertFalse(users.isActiveMember(departing.userId(), enterprise));
        assertTrue(users.isActiveMember(departing.userId(), other));
        assertEquals("active", users.findById(departing.userId()).orElseThrow().status());
        assertEquals(sessionVersion, users.findById(departing.userId()).orElseThrow().sessionVersion());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, (enterprise)).eq(AuditEventRow::getAction, "member.remove"))));
        for (var notice : notifications.candidates().stream().filter(value -> value.enterprise().equals(enterprise)).toList()) {
            notifications.deliver(notice);
            notifications.deliver(notice);
        }
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(NotificationSqlMapper.class).selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, (enterprise)).eq(NotificationRow::getUserId, (recipient.userId())))));
    }

    @Test
    void explicitCancellationOnlyChangesOpenTodos() {
        var departing = member("选择取消待办的成员", "member");
        var open = todos.create(departing, todo(departing.userId(), null));
        var ended = todos.create(departing, todo(departing.userId(), null));
        todos.status(departing, ended.id(), new TodoStatusRequest("completed", "已经完成"), 1);
        var impact = queries.impact(actor(admin), departing.userId());
        assertEquals("DEPENDENCIES_EXIST", assertThrows(ApiException.class, () -> remove(departing.userId(), impact, null, null, false)).code());
        remove(departing.userId(), impact, null, null, true);
        assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(TodoTableMapper.class).selectList(new LambdaQueryWrapper<TodoItemRow>().select(TodoItemRow::getStatus).eq(TodoItemRow::getId, (open.id()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(TodoTableMapper.class).selectList(new LambdaQueryWrapper<TodoItemRow>().select(TodoItemRow::getStatus).eq(TodoItemRow::getId, (ended.id()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals(4, count("todo_history"));
    }

    @Test
    void changedTodoRevisionOrReplacementRequiresAnotherImpactEvenWhenTheCountIsTheSame() {
        var departing = member("关联发生变化的成员", "member");
        var original = todos.create(departing, todo(departing.userId(), null));
        var first = queries.impact(actor(admin), departing.userId());
        todos.update(departing, original.id(), new TodoWriteRequest("修改后的待办", "正文已变化", departing.userId(), null, null, "normal", "manual", null, null, null), 1);
        assertEquals("DEPENDENCIES_CHANGED", assertThrows(ApiException.class, () -> remove(departing.userId(), first, null, null, true)).code());
        var second = queries.impact(actor(admin), departing.userId());
        todos.delete(departing, original.id(), 2);
        todos.create(departing, todo(departing.userId(), null));
        assertEquals(1, queries.impact(actor(admin), departing.userId()).openTodoCount());
        assertEquals("DEPENDENCIES_CHANGED", assertThrows(ApiException.class, () -> remove(departing.userId(), second, null, null, true)).code());
        assertEquals("active", enterprises.findMember(enterprise, departing.userId()).orElseThrow().status());
    }

    @Test
    void receiverChoicesFilterPermissionsAndAllTeamMembershipsBeforePagination() throws Exception {
        var departing = member("选择接收人的负责人", "enterprise-admin");
        var first = member("可接收甲", "builder");
        var second = member("可接收乙", "builder");
        var outside = member("可接收丙", "member");
        ownership.transfer(actor(admin), agent, departing.userId(), Long.parseLong(resourceRevision()));
        String team = team(departing.userId(), Set.of(departing.userId(), first.userId(), second.userId()));
        todos.create(departing, todo(departing.userId(), team));
        var page = queries.recipients(actor(admin), departing.userId(), "todo", "可接收", null, 1);
        assertTrue(page.hasMore());
        var last = queries.recipients(actor(admin), departing.userId(), "todo", "可接收", page.nextCursor(), 1);
        assertFalse(last.hasMore());
        assertEquals(Set.of(first.userId(), second.userId()), Set.of(page.items().getFirst().id(), last.items().getFirst().id()));
        assertThrows(ApiException.class, () -> queries.recipients(actor(admin), departing.userId(), "ownership", "可接收", page.nextCursor(), 1));
        var allowed = queries.recipients(actor(admin), departing.userId(), "ownership", "可接收", null, 30).items();
        assertFalse(allowed.stream().anyMatch(value -> value.id().equals(outside.userId())));
        var response = data(mvc.perform(get(path(departing.userId()) + "/removal-recipients").param("kind", "todo").cookie(cookie)).andExpect(status().isOk()).andReturn());
        for (var value : response.path("items")) {
            schemas.validate("MemberRemovalRecipient", value);
            assertEquals(2, value.size());
        }
        var impact = queries.impact(actor(admin), departing.userId());
        assertThrows(ApiException.class, () -> remove(departing.userId(), impact, outside.userId(), first.userId(), false));
        assertThrows(ApiException.class, () -> remove(departing.userId(), impact, admin, outside.userId(), false));
        assertEquals(departing.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getOwnerUserId).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
    }

    @Test
    void expiredTamperedAndOtherActorsTokensCannotRemoveTheMember() {
        var departing = member("凭证校验对象", "member");
        var otherAdmin = member("另一个管理员", "enterprise-admin");
        var impact = queries.impact(actor(admin), departing.userId());
        assertEquals("DEPENDENCIES_CHANGED", assertThrows(ApiException.class, () -> removal.remove(otherAdmin, departing.userId(), new MemberRemovalRequest(impact.impactToken(), null, null, false), Long.parseLong(impact.memberRevision()))).code());
        assertEquals("DEPENDENCIES_CHANGED", assertThrows(ApiException.class, () -> removal.remove(actor(admin), departing.userId(), new MemberRemovalRequest("错误" + impact.impactToken(), null, null, false), Long.parseLong(impact.memberRevision()))).code());
        Instant later = clock.instant().plusSeconds(601);
        try {
            doReturn(later).when(clock).instant();
            doReturn(later.toEpochMilli()).when(clock).millis();
            assertEquals("DEPENDENCIES_CHANGED", assertThrows(ApiException.class, () -> remove(departing.userId(), impact, null, null, false)).code());
        } finally {
            reset(clock);
        }
        assertEquals("active", enterprises.findMember(enterprise, departing.userId()).orElseThrow().status());
    }

    @Test
    void aManagerWithoutResourceAuthorityMustCompleteOwnershipHandoffBeforeRemoval() {
        var departing = member("原构建者", "builder");
        ownership.transfer(actor(admin), agent, departing.userId(), Long.parseLong(resourceRevision()));
        String readonly = role("仅查看待办", Set.of("todo.view"));
        permissions.replaceUserRoles(departing.userId(), enterprise, Set.of(readonly), Instant.now());
        var manager = member("只管理成员的人员", "member");
        String management = role("成员管理和待办查看", Set.of("enterprise.members.manage", "todo.view"));
        permissions.replaceUserRoles(manager.userId(), enterprise, Set.of(management), Instant.now());
        var limited = actor(manager.userId());
        var impact = queries.impact(limited, departing.userId());
        assertFalse(impact.canTransferOwnership());
        assertThrows(ResponseStatusException.class, () -> removal.remove(limited, departing.userId(), new MemberRemovalRequest(impact.impactToken(), admin, null, false), Long.parseLong(impact.memberRevision())));
        assertThrows(ResponseStatusException.class, () -> queries.impact(limited, admin));
        ownership.transfer(actor(admin), agent, admin, Long.parseLong(resourceRevision()));
        var fresh = queries.impact(limited, departing.userId());
        assertTrue(fresh.canTransferOwnership());
        removal.remove(limited, departing.userId(), new MemberRemovalRequest(fresh.impactToken(), null, null, false), Long.parseLong(fresh.memberRevision()));
        assertEquals("removed", enterprises.findMember(enterprise, departing.userId()).orElseThrow().status());
    }

    @Test
    void concurrentRemovalsPreserveOneEffectiveAdministratorAndAnActiveResourceOwner() throws Exception {
        var peer = member("必须保留的另一管理员", "enterprise-admin");
        var self = queries.impact(actor(admin), admin);
        var other = queries.impact(actor(admin), peer.userId());
        assertFalse(self.lastAdministrator());
        assertFalse(other.lastAdministrator());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attempt(start, () -> remove(admin, self, peer.userId(), null, false)));
            var second = pool.submit(() -> attempt(start, () -> remove(peer.userId(), other, null, null, false)));
            start.countDown();
            assertEquals(1, first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, permissions.activeAdministratorCount(enterprise));
        String owner = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getOwnerUserId).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList());
        assertTrue(users.isActiveMember(owner, enterprise));
        var last = queries.impact(actor(owner), owner);
        assertTrue(last.lastAdministrator());
        assertEquals("LAST_ADMIN_REQUIRED", assertThrows(ApiException.class, () -> removal.remove(actor(owner), owner, new MemberRemovalRequest(last.impactToken(), null, null, false), Long.parseLong(last.memberRevision()))).code());
    }

    @Test
    void outerRollbackRestoresOwnershipMembershipTodoHistoryAndNotificationsTogether() {
        var departing = member("回滚验收对象", "enterprise-admin");
        var recipient = member("回滚接收人", "builder");
        ownership.transfer(actor(admin), agent, departing.userId(), Long.parseLong(resourceRevision()));
        String team = team(departing.userId(), Set.of(departing.userId(), recipient.userId()));
        var value = todos.create(departing, todo(departing.userId(), team));
        var impact = queries.impact(actor(admin), departing.userId());
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            remove(departing.userId(), impact, admin, recipient.userId(), false);
            transaction.setRollbackOnly();
        });
        assertEquals("active", enterprises.findMember(enterprise, departing.userId()).orElseThrow().status());
        assertEquals(departing.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getOwnerUserId).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
        assertEquals(departing.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(EnterpriseTeamTableMapper.class).selectList(new LambdaQueryWrapper<EnterpriseTeamRow>().select(EnterpriseTeamRow::getOwnerUserId).eq(EnterpriseTeamRow::getId, (team))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
        assertEquals(departing.userId(), todoQueries.get(departing, value.id()).owner().id());
        assertEquals(1, count("todo_history"));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "notification"))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, (enterprise)).eq(AuditEventRow::getAction, "member.remove"))));
        remove(departing.userId(), impact, admin, recipient.userId(), false);
        assertEquals("removed", enterprises.findMember(enterprise, departing.userId()).orElseThrow().status());
    }

    @Test
    void aTokenCannotBeUsedInAnotherEnterpriseForTheSameUser() {
        var departing = member("同时属于两个企业的成员", "member");
        var impact = queries.impact(actor(admin), departing.userId());
        String other = provisioning.create("凭证不能跨用的企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        users.addMember(other, departing.userId(), "另一企业的同一成员", Instant.now());
        permissions.replaceUserRoles(departing.userId(), other, Set.of(permissions.builtinRoleId(other, "member")), Instant.now());
        var actor = new AuthContext(users.findById(admin).orElseThrow(), other, Set.copyOf(permissions.listPermissionCodes(admin, other)));
        assertEquals("DEPENDENCIES_CHANGED", assertThrows(ApiException.class, () -> removal.remove(actor, departing.userId(), new MemberRemovalRequest(impact.impactToken(), null, null, false), Long.parseLong(impact.memberRevision()))).code());
        assertTrue(users.isActiveMember(departing.userId(), enterprise));
        assertTrue(users.isActiveMember(departing.userId(), other));
    }

    @Test
    void aRecipientLosingTeamMembershipInvalidatesThePlanAndCannotReceiveItsTodo() throws Exception {
        var departing = member("团队资格变化的原负责人", "enterprise-admin");
        var recipient = member("即将离开团队的接收人", "builder");
        String team = team(departing.userId(), Set.of(departing.userId(), recipient.userId()));
        todos.create(departing, todo(departing.userId(), team));
        var impact = queries.impact(actor(admin), departing.userId());
        change(HttpMethod.PUT, base() + "/teams/" + team + "/members", Map.of("memberIds", List.of(departing.userId())), "1", key()).andExpect(status().isOk());
        assertEquals("DEPENDENCIES_CHANGED", assertThrows(ApiException.class, () -> remove(departing.userId(), impact, admin, recipient.userId(), false)).code());
        var fresh = queries.impact(actor(admin), departing.userId());
        assertTrue(queries.recipients(actor(admin), departing.userId(), "todo", null, null, 30).items().isEmpty());
        assertTrue(assertThrows(ApiException.class, () -> remove(departing.userId(), fresh, admin, recipient.userId(), false)).fieldErrors().containsKey("todoOwnerId"));
        assertEquals("active", enterprises.findMember(enterprise, departing.userId()).orElseThrow().status());
        remove(departing.userId(), fresh, admin, null, true);
    }

    @Test
    void identifierCaseCannotHandOffToTheDepartingMemberAndValidRecipientsKeepTheirStoredIds() {
        var departing = member("编号大小写验收对象", "builder");
        var recipient = member("正式编号接收人", "builder");
        ownership.transfer(actor(admin), agent, departing.userId(), Long.parseLong(resourceRevision()));
        var value = todos.create(departing, todo(departing.userId(), null));
        String uppercase = departing.userId().toUpperCase(Locale.ROOT);
        var impact = queries.impact(actor(admin), uppercase);
        assertTrue(assertThrows(ApiException.class, () -> remove(departing.userId(), impact, uppercase, recipient.userId(), false)).fieldErrors().containsKey("resourceOwnerId"));
        assertTrue(assertThrows(ApiException.class, () -> remove(uppercase, impact, admin, uppercase, false)).fieldErrors().containsKey("todoOwnerId"));
        assertEquals("active", enterprises.findMember(enterprise, departing.userId()).orElseThrow().status());
        remove(departing.userId(), impact, recipient.userId().toUpperCase(Locale.ROOT), recipient.userId().toUpperCase(Locale.ROOT), false);
        assertEquals(recipient.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getOwnerUserId).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
        assertEquals(recipient.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(TodoTableMapper.class).selectList(new LambdaQueryWrapper<TodoItemRow>().select(TodoItemRow::getOwnerUserId).eq(TodoItemRow::getId, (value.id()))).stream().map(fixtureRecord -> fixtureRecord.getOwnerUserId()).toList()));
        assertTrue(todoQueries.get(recipient, value.id()).allowedActions().contains("edit"));
    }

    private void remove(String member, MemberRemovalImpactView impact, String resourceOwner, String todoOwner, boolean cancel) {
        removal.remove(actor(admin), member, new MemberRemovalRequest(impact.impactToken(), resourceOwner, todoOwner, cancel), Long.parseLong(impact.memberRevision()));
    }

    private int attempt(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return 1;
        } catch (ResponseStatusException conflict) {
            assertTrue(Set.of(403, 404, 409).contains(conflict.getStatusCode().value()));
            return 0;
        }
    }

    private String role(String name, Set<String> codes) {
        String id = key();
        permissions.insertRole(id, enterprise, "removal_" + id.substring(0, 8), name, "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, id, codes);
        return id;
    }

    private AuthContext member(String name, String role) {
        var user = EnterpriseTestData.member(users, permissions, enterprise, "removal_" + key().substring(0, 8), "member-removal-test-password", name, List.of(permissions.builtinRoleId(enterprise, role)));
        return actor(user.id());
    }

    private AuthContext actor(String user) {
        return new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
    }

    private String team(String owner, Set<String> members) {
        String id = key();
        enterprises.insertTeam(id, enterprise, "交接团队" + id.substring(0, 8), "", "active", owner, Instant.now());
        enterprises.replaceTeamMembers(enterprise, id, members, Instant.now());
        return id;
    }

    private TodoWriteRequest todo(String owner, String team) {
        return new TodoWriteRequest("仅在待办内共享的事项", "由原成员确认后的待办内容", owner, team, null, "normal", "manual", null, null, null);
    }

    private String path(String user) {
        return base() + "/members/" + user;
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}

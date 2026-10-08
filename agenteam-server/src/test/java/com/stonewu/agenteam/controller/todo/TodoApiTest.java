package com.stonewu.agenteam.controller.todo;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.test.todo.TodoItemFixtureMapper;
import com.stonewu.agenteam.mapper.todo.TodoTableMapper;
import com.stonewu.agenteam.mapper.user.AppUserTableMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.todo.entity.TodoItemRow;
import com.stonewu.agenteam.model.todo.request.TodoStatusRequest;
import com.stonewu.agenteam.model.todo.request.TodoTransferRequest;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.model.todo.response.TodoView;
import com.stonewu.agenteam.model.user.entity.AppUserRow;
import com.stonewu.agenteam.service.enterprise.TeamDefinitionService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.service.todo.TodoManagementService;
import com.stonewu.agenteam.service.todo.TodoQueryService;
import com.stonewu.agenteam.service.todo.TodoSelectionService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实企业、会话与事务验证待办，不用管理员身份替代其他成员的访问资格。
 */
@Import(SharedEnterpriseTestEdition.class)
class TodoApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private TodoManagementService todos;

    @Autowired
    private TodoQueryService queries;

    @Autowired
    private TodoSelectionService selections;

    @Autowired
    private TeamDefinitionService teamChanges;

    @Autowired
    private EnterpriseMapper enterprises;

    @Autowired
    private NotificationDeliveryService notifications;

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

    @Test
    void noDeadlineRepeatedCompletionReopenAndDeleteKeepOneHistoryPerChange() throws Exception {
        String key = UUID.randomUUID().toString();
        var body = input(admin, null, null);
        var value = data(write(base() + "/todos", body, key).andExpect(status().isCreated()).andReturn());
        schemas.validate("Todo", value);
        assertEquals(value, data(write(base() + "/todos", body, key).andExpect(status().isCreated()).andReturn()));
        assertTrue(value.path("dueDate").isNull());
        assertEquals("pending", value.path("status").asText());
        assertEquals(1, count("todo_history"));
        String id = value.path("id").asText(), completeKey = UUID.randomUUID().toString();
        var completed = data(change(HttpMethod.PATCH, path(id) + "/status", new TodoStatusRequest("completed", "已核对资料"), "1", completeKey).andExpect(status().isOk()).andReturn());
        assertEquals("completed", completed.path("status").asText());
        assertFalse(completed.path("completedAt").isNull());
        assertEquals(completed, data(change(HttpMethod.PATCH, path(id) + "/status", new TodoStatusRequest("completed", "已核对资料"), "1", completeKey).andExpect(status().isOk()).andReturn()));
        assertEquals(2, count("todo_history"));
        change(HttpMethod.PATCH, path(id) + "/status", new TodoStatusRequest("completed", ""), "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        change(HttpMethod.PATCH, path(id) + "/status", new TodoStatusRequest("in_progress", ""), "2", UUID.randomUUID().toString()).andExpect(status().isConflict());
        var reopened = data(change(HttpMethod.PATCH, path(id) + "/status", new TodoStatusRequest("pending", "重新确认"), "2", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertTrue(reopened.path("completedAt").isNull());
        var history = data(mvc.perform(get(path(id) + "/history").cookie(cookie)).andExpect(status().isOk()).andReturn());
        for (var item : history.path("items")) {
            schemas.validate("TodoHistory", item);
        }
        assertTrue(history.toString().contains("已核对资料"));
        String deleteKey = UUID.randomUUID().toString();
        change(HttpMethod.DELETE, path(id), null, "3", deleteKey).andExpect(status().isOk());
        change(HttpMethod.DELETE, path(id), null, "3", deleteKey).andExpect(status().isOk());
        assertEquals(4, count("todo_history"));
        mvc.perform(get(path(id)).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void privateListsFilterBeforePaginationAndAdministratorsCannotReadAnotherPersonsTodo() throws Exception {
        var first = member("待办甲");
        var second = member("待办乙");
        var a = todos.create(first, input(first.userId(), null, null));
        todos.create(first, input(first.userId(), null, null));
        todos.create(second, input(second.userId(), null, null));
        mvc.perform(get(path(a.id())).cookie(cookie)).andExpect(status().isNotFound());
        var page = queries.list(first, "mine", null, null, null, null, 1);
        assertEquals(1, page.items().size());
        assertTrue(page.hasMore());
        var last = queries.list(first, "mine", null, null, null, page.nextCursor(), 1);
        assertEquals(1, last.items().size());
        assertFalse(last.hasMore());
        assertThrows(ApiException.class, () -> queries.list(second, "mine", null, null, null, page.nextCursor(), 1));
        assertThrows(ApiException.class, () -> queries.list(first, "mine", "completed", null, null, page.nextCursor(), 1));
        assertEquals(1, queries.list(second, "mine", null, null, null, null, 30).items().size());
        assertTrue(queries.list(actor(admin), "mine", null, null, null, null, 30).items().isEmpty());
    }

    @Test
    void teamReadersCannotTransferOtherPeoplesTodosAndMembershipChangesApplyImmediately() {
        var reader = member("团队查看人");
        var outside = member("团队外成员");
        String team = team(Set.of(admin, reader.userId()));
        var value = todos.create(actor(admin), input(admin, team, null));
        assertTrue(queries.get(reader, value.id()).allowedActions().isEmpty());
        assertThrows(ResponseStatusException.class, () -> todos.transfer(reader, value.id(), new TodoTransferRequest(reader.userId(), ""), 1));
        assertThrows(ResponseStatusException.class, () -> queries.get(outside, value.id()));
        assertEquals(1, queries.list(reader, "team", null, team, null, null, 30).items().size());
        var assigned = todos.create(actor(admin), input(reader.userId(), team, null));
        assertTrue(queries.get(reader, assigned.id()).allowedActions().contains("complete"));
        todos.status(reader, assigned.id(), new TodoStatusRequest("completed", "本人完成"), 1);
        enterprises.replaceTeamMembers(enterprise, team, Set.of(admin), Instant.now());
        assertThrows(ResponseStatusException.class, () -> queries.get(reader, value.id()));
        assertThrows(ResponseStatusException.class, () -> queries.get(reader, assigned.id()));
    }

    @Test
    void teamMaintenanceStillRequiresMembershipAndAssigneesMustBelongToThatTeam() {
        var manager = member("团队维护人");
        var outsider = member("其他负责人");
        String team = team(Set.of(admin, manager.userId()));
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "todo_team_manager", "维护团队待办", "", DataScope.TEAM, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("todo.view", "todo.manage", "todo.team_view", "todo.team_manage"));
        permissions.replaceUserRoles(manager.userId(), enterprise, Set.of(role), Instant.now());
        manager = actor(manager.userId());
        var value = todos.create(actor(admin), input(admin, team, null));
        final var currentManager = manager;
        assertTrue(queries.get(manager, value.id()).allowedActions().contains("transfer"));
        assertThrows(ApiException.class, () -> todos.transfer(currentManager, value.id(), new TodoTransferRequest(outsider.userId(), ""), 1));
        var moved = todos.transfer(manager, value.id(), new TodoTransferRequest(manager.userId(), "接手处理"), 1);
        assertEquals(manager.userId(), moved.owner().id());
        assertEquals("接手处理", queries.history(manager, moved.id(), null, 30).items().getFirst().reason());
    }

    @Test
    void transferredTodoCanBeEditedWithoutExposingOrRequiringThePrivateSourceConversation() throws Exception {
        var recipient = member("待办受让人");
        var accepted = data(write(base() + "/conversations", Map.of("agentId", agent, "input", input("原始私有内容只属于创建人")), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn());
        String conversation = accepted.path("conversationId").asText(), message = accepted.path("inputMessageId").asText();
        var source = new TodoWriteRequest("需要转交的待办", "只共享这段确认后的摘要", admin, null, null, "normal", "message", conversation, message, null);
        var value = todos.create(actor(admin), source);
        assertTrue(value.sourceAccessible());
        var moved = todos.transfer(actor(admin), value.id(), new TodoTransferRequest(recipient.userId(), "请协助处理"), 1);
        var visible = queries.get(recipient, moved.id());
        schemas.validate("Todo", json.valueToTree(visible));
        assertFalse(visible.sourceAccessible());
        assertNull(visible.sourceConversationId());
        assertFalse(json.writeValueAsString(visible).contains(conversation));
        assertFalse(json.writeValueAsString(visible).contains("原始私有内容"));
        String todoOnlyRole = UUID.randomUUID().toString();
        permissions.insertRole(todoOnlyRole, enterprise, "todo_only", "仅处理本人待办", "", DataScope.OWN, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, todoOnlyRole, Set.of("todo.view", "todo.manage"));
        permissions.replaceUserRoles(recipient.userId(), enterprise, Set.of(todoOnlyRole), Instant.now());
        assertFalse(queries.get(recipient, moved.id()).sourceAccessible());
        var edit = new TodoWriteRequest("受让人补充标题", visible.description(), recipient.userId(), null, null, "high", "message", null, null, null);
        assertEquals(edit.title(), todos.update(recipient, value.id(), edit, 2).title());
        assertFalse(json.writeValueAsString(queries.history(recipient, value.id(), null, 30)).contains(conversation));
        assertThrows(ResponseStatusException.class, () -> todos.create(recipient, source));
        assertThrows(ApiException.class, () -> todos.update(recipient, value.id(), input(recipient.userId(), null, null), 3));
        var forged = new TodoWriteRequest("错误的工作流来源", "", admin, null, null, "normal", "workflow", conversation, null, accepted.path("runId").asText());
        assertThrows(ApiException.class, () -> todos.create(actor(admin), forged));
    }

    @Test
    void rollbackAndRepeatedRequestsDoNotDuplicateHistoryOrAssignmentNotifications() throws Exception {
        var recipient = member("通知接收人");
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            todos.create(actor(admin), input(recipient.userId(), null, null));
            transaction.setRollbackOnly();
        });
        assertEquals(0, count("todo_item"));
        assertEquals(0, count("todo_history"));
        assertEquals(0, notificationJobs());
        String key = UUID.randomUUID().toString();
        var input = input(recipient.userId(), null, null);
        var value = data(write(base() + "/todos", input, key).andExpect(status().isCreated()).andReturn());
        assertEquals(value, data(write(base() + "/todos", input, key).andExpect(status().isCreated()).andReturn()));
        assertEquals(1, count("todo_item"));
        assertEquals(1, count("todo_history"));
        assertEquals(1, notificationJobs());
        var pending = notifications.candidates().stream().filter(item -> item.enterprise().equals(enterprise)).toList();
        for (var item : pending) {
            notifications.deliver(item);
            notifications.deliver(item);
        }
        assertEquals(1, count("notification"));
        assertEquals(recipient.userId(), DataAccessUtils.nullableSingleResult(databaseAccess.mapper(NotificationSqlMapper.class).selectList(new LambdaQueryWrapper<NotificationRow>().select(NotificationRow::getUserId).eq(NotificationRow::getEnterpriseId, (enterprise))).stream().map(fixtureRecord -> fixtureRecord.getUserId()).toList()));
    }

    @Test
    void concurrentCompletionAndTransferOnlyAllowOneRevisionToWin() throws Exception {
        var recipient = member("并发受让人");
        var value = todos.create(actor(admin), input(admin, null, null));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var complete = pool.submit(() -> attempt(start, () -> todos.status(actor(admin), value.id(), new TodoStatusRequest("completed", "完成"), 1)));
            var transfer = pool.submit(() -> attempt(start, () -> todos.transfer(actor(admin), value.id(), new TodoTransferRequest(recipient.userId(), "转交"), 1)));
            start.countDown();
            assertEquals(1, complete.get(20, TimeUnit.SECONDS) + transfer.get(20, TimeUnit.SECONDS));
        }
        assertEquals("2", queries.get(actor(admin), value.id()).revision());
        assertEquals(2, count("todo_history"));
    }

    @Test
    void datesAndDatabaseConstraintsRejectInvalidValuesWithoutAutomaticallyEndingOverdueTodos() {
        var overdue = todos.create(actor(admin), input(admin, null, "2024-02-29"));
        assertEquals("pending", overdue.status());
        assertThrows(ApiException.class, () -> todos.create(actor(admin), input(admin, null, "2025-02-29")));
        String other = provisioning.create("其他待办企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        var foreign = EnterpriseTestData.member(users, permissions, other, "foreign_todo_" + UUID.randomUUID().toString().substring(0, 8), "todo-test-password-2026", "其他企业成员", List.of(permissions.builtinRoleId(other, "member")));
        assertThrows(ApiException.class, () -> todos.create(actor(admin), input(foreign.id(), null, null)));
        assertThrows(DataAccessException.class, () -> databaseAccess.mapper(TodoTableMapper.class).update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getId, (overdue.id())).set(TodoItemRow::getStatus, "completed")));
        assertThrows(DataAccessException.class, () -> databaseAccess.mapper(TodoTableMapper.class).update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getId, (overdue.id())).set(TodoItemRow::getSourceMessageId, "无来源会话的消息")));
        assertThrows(DataAccessException.class, () -> databaseAccess.mapper(TodoTableMapper.class).update(new LambdaUpdateWrapper<TodoItemRow>().eq(TodoItemRow::getId, (overdue.id())).set(TodoItemRow::getOwnerUserId, (foreign.id()))));
        assertEquals("pending", queries.get(actor(admin), overdue.id()).status());
        assertEquals(1, count("todo_item"));
    }

    @Test
    void assigneeChoicesExposeOnlyActiveEnterpriseNamesAndBindPaginationToTheCurrentOperation() throws Exception {
        var chooser = member("可选择人员甲");
        var available = member("可选择人员乙");
        var disabled = member("可选择人员丙");
        var inactive = member("可选择人员丁");
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "todo_selector", "处理本人待办", "", DataScope.OWN, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("todo.view", "todo.manage"));
        permissions.replaceUserRoles(chooser.userId(), enterprise, Set.of(role), Instant.now());
        var current = actor(chooser.userId());
        databaseAccess.mapper(IdentityQueryMapper.class).update(new LambdaUpdateWrapper<EnterpriseMemberRow>().eq(EnterpriseMemberRow::getEnterpriseId, (enterprise)).eq(EnterpriseMemberRow::getUserId, (disabled.userId())).set(EnterpriseMemberRow::getStatus, "disabled"));
        databaseAccess.mapper(AppUserTableMapper.class).update(new LambdaUpdateWrapper<AppUserRow>().eq(AppUserRow::getId, (inactive.userId())).set(AppUserRow::getStatus, "disabled"));
        String other = provisioning.create("其他人员企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        EnterpriseTestData.member(users, permissions, other, "foreign_choice_" + UUID.randomUUID().toString().substring(0, 8), "todo-test-password-2026", "可选择人员戊", List.of(permissions.builtinRoleId(other, "member")));
        var page = selections.assignees(current, null, null, "可选择人员", null, 1);
        assertEquals(1, page.items().size());
        assertTrue(page.hasMore());
        var next = selections.assignees(current, null, null, "可选择人员", page.nextCursor(), 1);
        assertFalse(next.hasMore());
        assertEquals(Set.of(chooser.userId(), available.userId()), Set.of(page.items().getFirst().id(), next.items().getFirst().id()));
        assertThrows(ApiException.class, () -> selections.assignees(available, null, null, "可选择人员", page.nextCursor(), 1));
        assertThrows(ApiException.class, () -> selections.assignees(current, null, null, "不同查询", page.nextCursor(), 1));
        var response = data(mvc.perform(get(base() + "/todos/assignees").param("query", "可选择人员").cookie(cookie)).andExpect(status().isOk()).andReturn());
        for (var item : response.path("items")) {
            schemas.validate("TodoOption", item);
            assertEquals(2, item.size());
        }
    }

    @Test
    void teamChoicesAndTransfersUseCurrentMembershipAndNeverOpenAnotherPersonsPrivateTodo() throws Exception {
        var reader = member("选项查看人");
        var outside = member("选项其他人");
        String joined = team(Set.of(admin, reader.userId()));
        String unjoined = team(Set.of(outside.userId()));
        assertEquals(List.of(joined), selections.teams(reader, "filter", null, null, 30).items().stream().map(item -> item.id()).toList());
        assertThrows(ResponseStatusException.class, () -> selections.teams(reader, "assign", null, null, 30));
        var personal = todos.create(actor(admin), input(admin, null, null));
        var shared = todos.create(actor(admin), input(admin, joined, null));
        assertThrows(ResponseStatusException.class, () -> selections.assignees(reader, personal.id(), null, null, null, 30));
        assertThrows(ResponseStatusException.class, () -> selections.assignees(reader, shared.id(), joined, null, null, 30));
        var assigned = todos.create(actor(admin), input(reader.userId(), joined, null));
        assertEquals(Set.of(admin, reader.userId()), Set.copyOf(selections.assignees(reader, assigned.id(), joined, null, null, 30).items().stream().map(item -> item.id()).toList()));
        assertThrows(ResponseStatusException.class, () -> selections.assignees(reader, assigned.id(), unjoined, null, null, 30));
        var response = data(mvc.perform(get(base() + "/todos/teams").param("purpose", "assign").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, response.path("items").size());
        schemas.validate("TodoOption", response.path("items").get(0));
        enterprises.replaceTeamMembers(enterprise, joined, Set.of(admin), Instant.now());
        assertTrue(selections.teams(reader, "filter", null, null, 30).items().isEmpty());
        assertThrows(ResponseStatusException.class, () -> selections.assignees(reader, assigned.id(), joined, null, null, 30));
    }

    @Test
    void bothMembershipEditorsRequireOpenTodosToBeHandledAndTeamDeletionCountsRealWork() throws Exception {
        var owner = member("即将移出团队的负责人");
        String team = team(Set.of(admin, owner.userId()));
        var value = todos.create(actor(admin), input(owner.userId(), team, null));
        String membersPath = base() + "/teams/" + team + "/members";
        change(HttpMethod.PUT, membersPath, Map.of("memberIds", List.of(admin)), "1", UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.counts.openTodos").value(1));
        String memberRevision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(IdentityQueryMapper.class).selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().select(EnterpriseMemberRow::getRevision).eq(EnterpriseMemberRow::getEnterpriseId, (enterprise)).eq(EnterpriseMemberRow::getUserId, (owner.userId()))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        change(HttpMethod.PATCH, base() + "/members/" + owner.userId(), Map.of("teamIds", List.of()), memberRevision, UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.counts.openTodos").value(1));
        change(HttpMethod.PUT, base() + "/teams/" + team, Map.of("name", "不能丢弃未处理的负责人", "description", "", "ownerUserId", admin, "memberIds", List.of(admin)), "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(Set.of(admin, owner.userId()), Set.copyOf(enterprises.findTeam(enterprise, team).orElseThrow().memberUserIds()));
        assertEquals(memberRevision, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(IdentityQueryMapper.class).selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().select(EnterpriseMemberRow::getRevision).eq(EnterpriseMemberRow::getEnterpriseId, (enterprise)).eq(EnterpriseMemberRow::getUserId, (owner.userId()))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList()));
        change(HttpMethod.DELETE, base() + "/teams/" + team, null, "1", UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.counts.openTodos").value(1));
        todos.status(owner, value.id(), new TodoStatusRequest("completed", "移出团队前完成"), 1);
        change(HttpMethod.PUT, membersPath, Map.of("memberIds", List.of()), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        change(HttpMethod.DELETE, base() + "/teams/" + team, null, "2", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(2, count("todo_history"));
        assertEquals("completed", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(TodoTableMapper.class).selectList(new LambdaQueryWrapper<TodoItemRow>().select(TodoItemRow::getStatus).eq(TodoItemRow::getId, (value.id()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
    }

    @Test
    void concurrentTransferAndTeamDepartureCannotLeaveAnOpenTodoOwnedOutsideItsTeam() throws Exception {
        var recipient = member("并发移出团队的人");
        String team = team(Set.of(admin, recipient.userId()));
        var value = todos.create(actor(admin), input(admin, team, null));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var transfer = pool.submit(() -> organizationAttempt(start, () -> todos.transfer(actor(admin), value.id(), new TodoTransferRequest(recipient.userId(), "接手"), 1)));
            var departure = pool.submit(() -> organizationAttempt(start, () -> teamChanges.members(actor(admin), team, List.of(admin), 1)));
            start.countDown();
            assertEquals(1, transfer.get(20, TimeUnit.SECONDS) + departure.get(20, TimeUnit.SECONDS));
        }
        assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(TodoItemFixtureMapper.class).todoApiConcurrentTransferAndTeamDepartureCannotLeaveAnOpenTodoOwnedOutsideItsTeamObject(enterprise)));
    }

    @Test
    void assigningAndEditingUseStoredMemberAndTeamIdsAndSelfTransferDoesNotCreateHistory() {
        var owner = member("编号写法不同的负责人");
        String team = team(Set.of(admin, owner.userId()));
        var value = todos.create(actor(admin), input(owner.userId().toUpperCase(Locale.ROOT), team.toUpperCase(Locale.ROOT), null));
        assertEquals(owner.userId(), value.owner().id());
        assertEquals(team, value.teamId());
        assertTrue(queries.get(owner, value.id()).allowedActions().contains("edit"));
        var updated = todos.update(owner, value.id(), input(owner.userId().toUpperCase(Locale.ROOT), team.toUpperCase(Locale.ROOT), null), 1);
        assertEquals(team, updated.teamId());
        assertEquals(owner.userId(), updated.owner().id());
        int history = count("todo_history"), jobs = notificationJobs();
        var unchanged = todos.transfer(owner, value.id(), new TodoTransferRequest(owner.userId().toUpperCase(Locale.ROOT), "仍由本人处理"), 2);
        assertEquals("2", unchanged.revision());
        assertEquals(history, count("todo_history"));
        assertEquals(jobs, notificationJobs());
    }

    private int organizationAttempt(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return 1;
        } catch (ApiException conflict) {
            assertTrue(Set.of("DEPENDENCIES_EXIST", "VALIDATION_FAILED").contains(conflict.code()));
            return 0;
        }
    }

    private TodoWriteRequest input(String owner, String team, String date) {
        return new TodoWriteRequest("核对本次事项", "由用户确认的待办正文", owner, team, date, "normal", "manual", null, null, null);
    }

    private AuthContext member(String name) {
        var user = EnterpriseTestData.member(users, permissions, enterprise, "todo_" + UUID.randomUUID().toString().substring(0, 8), "todo-test-password-2026", name, List.of(permissions.builtinRoleId(enterprise, "member")));
        return actor(user.id());
    }

    private AuthContext actor(String user) {
        return new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
    }

    private String team(Set<String> members) {
        String id = UUID.randomUUID().toString();
        enterprises.insertTeam(id, enterprise, "验收团队" + id.substring(0, 8), "", "active", admin, Instant.now());
        enterprises.replaceTeamMembers(enterprise, id, members, Instant.now());
        return id;
    }

    private String path(String id) {
        return base() + "/todos/" + id;
    }

    private int notificationJobs() {
        return Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "notification")));
    }

    private int attempt(CountDownLatch start, Supplier<TodoView> action) throws InterruptedException {
        start.await();
        try {
            action.get();
            return 1;
        } catch (ApiException conflict) {
            assertEquals("VERSION_CONFLICT", conflict.code());
            return 0;
        }
    }
}

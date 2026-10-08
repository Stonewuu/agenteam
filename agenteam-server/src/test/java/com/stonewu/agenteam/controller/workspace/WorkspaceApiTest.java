package com.stonewu.agenteam.controller.workspace;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileSqlMapper;
import com.stonewu.agenteam.mapper.permission.SysRoleTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.mapper.test.agent.AgentHireFixtureMapper;
import com.stonewu.agenteam.mapper.test.enterprise.ResourceFixtureMapper;
import com.stonewu.agenteam.mapper.test.execution.AgentConversationFixtureMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.SysRoleRow;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.model.workspace.response.SearchResultView;
import com.stonewu.agenteam.service.todo.TodoManagementService;
import com.stonewu.agenteam.service.workspace.HomeQueryService;
import com.stonewu.agenteam.service.workspace.WorkspaceSearchService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证真实归属、查询前授权、计数和选择顺序，不用模拟列表代替数据库查询。
 */
@Import(SharedEnterpriseTestEdition.class)
class WorkspaceApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private HomeQueryService home;

    @Autowired
    private WorkspaceSearchService search;

    @Autowired
    private TodoManagementService todos;

    @Autowired
    private EnterpriseMapper enterprises;

    @Autowired
    private ConversationMapper conversations;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void homeReturnsThreeRowsAndRealCountsAndCompletionUsesTheCurrentRevision() throws Exception {
        for (int i = 0; i < 5; i++) {
            todos.create(actor(admin), todo(admin, null, "待处理事项" + i));
        }
        var other = member(Set.of("workspace.view", "todo.view", "todo.manage"));
        todos.create(actor(admin), todo(other.userId(), null, "交给其他成员的事项"));
        for (int i = 0; i < 4; i++) {
            conversation(admin, "最近对话" + i, Instant.now().plusSeconds(i));
        }
        String archived = conversation(admin, "已归档对话", Instant.now().plusSeconds(10));
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (archived)).set(AgentConversationRow::getStatus, "archived"));
        write(base() + "/schedules", schedule(true), key()).andExpect(status().isCreated());
        write(base() + "/schedules", schedule(false), key()).andExpect(status().isCreated());
        var value = page("/home");
        schemas.validate("HomeSummary", value);
        assertEquals(5, value.path("openTodoCount").asInt());
        assertEquals(3, value.path("todos").size());
        assertEquals(3, value.path("recentConversations").size());
        assertEquals("最近对话3", value.at("/recentConversations/0/title").asText());
        assertEquals(1, value.path("enabledScheduleCount").asInt());
        assertFalse(value.toString().contains("交给其他成员"));
        var todo = value.path("todos").get(0);
        String path = base() + "/todos/" + todo.path("id").asText() + "/status";
        change(HttpMethod.PATCH, path, Map.of("status", "completed", "reason", "已完成"), "1", key()).andExpect(status().isOk());
        change(HttpMethod.PATCH, path, Map.of("status", "pending", "reason", "旧页面操作"), "1", key()).andExpect(status().isConflict());
        assertEquals(4, page("/home").path("openTodoCount").asInt());
        String elsewhere = provisioning.create("独立首页企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        assertEquals(0, home.get(new AuthContext(users.findById(admin).orElseThrow(), elsewhere, Set.of())).openTodoCount());
    }

    @Test
    void homeUsesCurrentPermissionsAndTeamMembershipBeforeTakingThreeRows() {
        var viewer = member(Set.of("workspace.view", "todo.view", "todo.manage"));
        String team = key();
        enterprises.insertTeam(team, enterprise, "首页实际团队", "", "active", admin, Instant.now());
        enterprises.replaceTeamMembers(enterprise, team, Set.of(admin, viewer.userId()), Instant.now());
        for (int i = 0; i < 4; i++) {
            todos.create(actor(admin), todo(viewer.userId(), team, "无团队权限不可见" + i));
        }
        todos.create(actor(admin), todo(viewer.userId(), null, "本人实际待办"));
        var first = home.get(viewer);
        assertEquals(1, first.openTodoCount());
        assertEquals("本人实际待办", first.todos().getFirst().title());
        String role = permissions.listUserRoleIds(viewer.userId(), enterprise).getFirst();
        permissions.replaceRolePermissions(enterprise, role, Set.of("workspace.view", "todo.view", "todo.manage", "todo.team_view"));
        assertEquals(5, home.get(viewer).openTodoCount());
        enterprises.replaceTeamMembers(enterprise, team, Set.of(admin), Instant.now());
        assertEquals(1, home.get(viewer).openTodoCount());
        permissions.replaceRolePermissions(enterprise, role, Set.of("workspace.view"));
        assertTrue(home.get(viewer).todos().isEmpty());
        assertTrue(home.get(viewer).recentConversations().isEmpty());
        assertTrue(home.get(viewer).employees().isEmpty());
        assertEquals(0, home.get(viewer).enabledScheduleCount());
    }

    @Test
    void defaultEmployeeUsesRealUseTimeAndSkipsPausedOrInvalidEntriesBeforeLimit() throws Exception {
        databaseAccess.mapper(AgentHireSqlMapper.class).update(new LambdaUpdateWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, (enterprise)).eq(AgentHireRow::getUserId, (admin)).eq(AgentHireRow::getAgentId, (agent)).set(AgentHireRow::getLastUsedAt, (Timestamp.from(Instant.now().minusSeconds(3600)))).set(AgentHireRow::getHiredAt, (Timestamp.from(Instant.now().minusSeconds(7200)))));
        for (int i = 0; i < 4; i++) {
            String id = newAgent("最近暂停员工" + i);
            hires.establish(enterprise, admin, id, Instant.now());
            databaseAccess.mapper(AgentHireFixtureMapper.class).workspaceApiDefaultEmployeeUsesRealUseTimeAndSkipsPausedOrInvalidEntriesBeforeLimitUpdate4(enterprise, id);
        }
        String newHire = newAgent("新雇佣员工");
        hires.establish(enterprise, admin, newHire, Instant.now());
        var value = page("/home");
        assertEquals(2, value.path("employees").size());
        assertEquals(agent, value.at("/employees/0/agentId").asText());
        assertEquals(newHire, value.at("/employees/1/agentId").asText());
        assertFalse(value.toString().contains("最近暂停员工"));
        databaseAccess.mapper(ResourceVersionSqlMapper.class).update(new LambdaUpdateWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getEnterpriseId, (enterprise)).eq(ResourceVersionRow::getId, (version)).set(ResourceVersionRow::getStatus, "revoked"));
        assertEquals(newHire, page("/home").at("/employees/0/agentId").asText());
        databaseAccess.mapper(ModelProfileSqlMapper.class).update(new LambdaUpdateWrapper<ModelProfileRow>().eq(ModelProfileRow::getEnterpriseId, (enterprise)).set(ModelProfileRow::getEnabled, false));
        assertTrue(page("/home").path("employees").isEmpty());
    }

    @Test
    void searchFiltersResourceOwnershipBeforeLimitAndDoesNotBorrowAnotherOperationScope() throws Exception {
        var viewer = member(Set.of("workspace.view", "capabilities.view", "agent.view"));
        String role = permissions.listUserRoleIds(viewer.userId(), enterprise).getFirst();
        for (int i = 0; i < 12; i++) {
            draftResource(viewer.userId(), "匹配本人资源" + i, Instant.now().minusSeconds(60));
        }
        for (int i = 0; i < 15; i++) {
            draftResource(admin, "匹配无权资源" + i, Instant.now());
        }
        String wide = key();
        permissions.insertRole(wide, enterprise, wide, "其他操作企业范围", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, wide, Set.of("audit.view"));
        databaseAccess.mapper(SysRoleTableMapper.class).update(new LambdaUpdateWrapper<SysRoleRow>().eq(SysRoleRow::getId, (role)).set(SysRoleRow::getDataScope, "own"));
        permissions.replaceUserRoles(viewer.userId(), enterprise, Set.of(role, wide), Instant.now());
        var result = search.get(viewer, "匹配");
        schemas.validate("SearchResult", json.valueToTree(result));
        var rows = items(result, "resources");
        assertEquals(10, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.name().startsWith("匹配本人")));
        assertTrue(rows.stream().allMatch(row -> row.resourceKind().equals("agent")));
        assertFalse(result.toString().contains("无权资源"));
        permissions.replaceRolePermissions(enterprise, role, Set.of("workspace.view", "capabilities.view", "agent.create"));
        assertTrue(items(search.get(viewer, "匹配"), "resources").isEmpty());
        mvc.perform(get(base() + "/search").param("query", "查".repeat(101)).cookie(cookie)).andExpect(status().isUnprocessableEntity());
        assertTrue(page("/search?query=").path("groups").size() <= 3);
    }

    @Test
    void searchReturnsPublicEmployeeAndPrivateConversationWithoutInternalInstructions() throws Exception {
        configureAgent(config -> config.put("icon", "NotebookPen").put("color", "mint"));
        var viewer = member(Set.of("workspace.view", "agent.run", "conversation.view"));
        hires.establish(enterprise, viewer.userId(), agent, Instant.now());
        String own = conversation(viewer.userId(), "固定本人历史", Instant.now());
        databaseAccess.mapper(ConversationSqlMapper.class).update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, (own)).set(AgentConversationRow::getStatus, "archived"));
        conversation(admin, "固定其他成员的私有历史", Instant.now().plusSeconds(10));
        configureAgent(config -> config.put("icon", "Telescope").put("color", "blue"));
        var result = search.get(viewer, "固定");
        schemas.validate("SearchResult", json.valueToTree(result));
        assertEquals(1, items(result, "conversations").size());
        assertEquals(own, items(result, "conversations").getFirst().targetId());
        assertEquals("NotebookPen", items(result, "conversations").getFirst().icon());
        assertEquals("mint", items(result, "conversations").getFirst().color());
        assertEquals(1, items(result, "employees").size());
        assertEquals("Telescope", items(result, "employees").getFirst().icon());
        assertEquals("blue", items(result, "employees").getFirst().color());
        assertTrue(items(result, "resources").isEmpty());
        assertFalse(result.toString().contains("内部指令"));
        assertFalse(result.toString().contains("其他成员"));
        databaseAccess.mapper(AgentConversationFixtureMapper.class).workspaceApiSearchReturnsPublicEmployeeAndPrivateConversationWithoutInternalInstructionsUpdate(own);
        assertTrue(items(search.get(viewer, "固定"), "conversations").isEmpty());
        String other = provisioning.create("另一搜索企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        var otherResult = data(mvc.perform(get("/api/v1/enterprises/" + other + "/search").param("query", "固定").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertTrue(otherResult.path("groups").isEmpty());
    }

    private JsonNode page(String suffix) throws Exception {
        return data(mvc.perform(get(base() + suffix).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private AuthContext actor(String user) {
        return new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
    }

    private AuthContext member(Set<String> codes) {
        String id = key();
        permissions.insertRole(id, enterprise, id, "首页查询角色" + id.substring(0, 8), "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, id, codes);
        return actor(EnterpriseTestData.member(users, permissions, enterprise, "workspace_" + key().substring(0, 8), "workspace-test-password-only", "首页成员", List.of(id)).id());
    }

    private TodoWriteRequest todo(String owner, String team, String title) {
        return new TodoWriteRequest(title, "已确认的待办内容", owner, team, null, "normal", "manual", null, null, null);
    }

    private String conversation(String owner, String title, Instant now) {
        String id = key();
        hires.establish(enterprise, owner, agent, now);
        conversations.create(id, enterprise, owner, agent, version, hires.forAgent(enterprise, owner, agent, false).orElseThrow().id(), title, "normal", now);
        return id;
    }

    private String newAgent(String name) throws Exception {
        var config = json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getEnterpriseId, (enterprise)).eq(ResourceDraftRow::getResourceId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList()));
        String id = data(write(base() + "/resources", Map.of("kind", "agent", "name", name, "description", "公开员工介绍", "tagIds", List.of(), "config", config), key()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        change(HttpMethod.POST, base() + "/resources/" + id + "/publish", Map.of("releaseNote", "正式首页验收"), "1", key()).andExpect(status().isCreated());
        return id;
    }

    private void draftResource(String owner, String name, Instant updated) {
        databaseAccess.mapper(ResourceFixtureMapper.class).workspaceApiDraftResourceUpdate(key(), enterprise, name, owner, Timestamp.from(updated));
    }

    private Map<String, Object> schedule(boolean enabled) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", "首页真实计划" + key().substring(0, 8));
        result.put("hireId", hires.forAgent(enterprise, admin, agent, false).orElseThrow().id());
        result.put("agentVersionId", null);
        result.put("inputText", "整理工作");
        result.put("frequency", "daily");
        result.put("localDate", null);
        result.put("localTime", "09:15");
        result.put("weekdays", List.of());
        result.put("monthDay", null);
        result.put("timezone", "Asia/Shanghai");
        result.put("enabled", enabled);
        result.put("maxRetries", 0);
        return result;
    }

    private List<SearchResultView.Item> items(SearchResultView result, String key) {
        return result.groups().stream().filter(group -> group.key().equals(key)).flatMap(group -> group.items().stream()).toList();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}

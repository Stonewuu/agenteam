package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.execution.RunJobSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.test.background.BackgroundJobFixtureMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.agent.EmployeeQueryService;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.stonewu.agenteam.service.schedule.ScheduleQueryService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固定版本的间接引用和成员停用必须影响真实计划，同时保持私人内容不可见。
 */
@Import(SharedEnterpriseTestEdition.class)
class ScheduleRevocationApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private ResourceGrantService grants;

    @Autowired
    private ScheduleManagementService plans;

    @Autowired
    private ScheduleTriggerService triggers;

    @Autowired
    private ScheduleActionWorker actionWorker;

    @Autowired
    private ScheduleQueryService scheduleQueries;

    @Autowired
    private EmployeeQueryService employees;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void runPermissionAllowsSelectingOwnHiredEmployeeWithoutOpeningTheMarket() {
        grants.replace(actor(admin), agent, Long.parseLong(resourceRevision()), List.of(new ResourceGrantSpec("enterprise", enterprise, "use")));
        String role = UUID.randomUUID().toString();
        permissions.insertRole(role, enterprise, "schedule_runner", "计划执行人", "仅使用本人已雇佣员工", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, role, Set.of("agent.run", "schedule.view", "schedule.manage"));
        var member = EnterpriseTestData.member(users, permissions, enterprise, "picker_" + UUID.randomUUID().toString().substring(0, 8), "plan-picker-test-password-2026", "计划执行人", List.of(role));
        var actor = actor(member.id());
        assertTrue(employees.list(actor, "mine", null, List.of(), null, 30).items().isEmpty());
        hires.establish(enterprise, member.id(), agent, Instant.now());
        assertEquals(agent, employees.list(actor, "mine", null, List.of(), null, 30).items().getFirst().agentId());
        assertTrue(employees.detail(actor, agent).canRun());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> employees.list(actor, "market", null, List.of(), null, 30)).getStatusCode().value());
    }

    @Test
    void duplicateIndirectReferencesCountEachEnabledPlanOnceAndRevocationPausesThem() throws Exception {
        String leaf = agent, leafVersion = version;
        var workflow = Map.of("icon", "GitBranch", "color", "blue", "nodes", List.of(node("start", "start", Map.of("inputSchema", Map.of("type", "object"))), node("first", "agent", Map.of("agentVersionId", leafVersion, "inputMapping", Map.of("text", "处理第一项"))), node("second", "agent", Map.of("agentVersionId", leafVersion, "inputMapping", Map.of("text", "处理第二项"))), node("end", "end", Map.of("outputMapping", Map.of("text", "处理完成")))), "edges", List.of(edge("start", "first"), edge("first", "second"), edge("second", "end")));
        String flowVersion = publish(create("workflow", workflow));
        var config = (ObjectNode) json.readTree(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getConfigJson).eq(ResourceDraftRow::getResourceId, (leaf))).stream().map(fixtureRecord -> fixtureRecord.getConfigJson()).toList()));
        config.put("agentType", "workflow").putNull("modelProfileId").put("entryWorkflowVersionId", flowVersion);
        config.remove("temperature");
        config.set("workflowVersionIds", json.valueToTree(List.of(flowVersion)));
        agent = create("agent", config);
        version = publish(agent);
        String hire = hires.establish(enterprise, admin, agent, Instant.now()).id();
        plans.create(actor(admin), request(hire, true));
        plans.create(actor(admin), request(hire, true));
        plans.create(actor(admin), request(hire, false));
        var impactedLeaf = impact(leaf);
        assertEquals(2, impactedLeaf.path("enabledScheduleCount").asInt());
        assertEquals(2, impact(agent).path("enabledScheduleCount").asInt());
        assertFalse(impactedLeaf.toString().contains("私有计划正文"));
        String revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getRevision).eq(ResourceRow::getId, (leaf))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        change(HttpMethod.POST, base() + "/resources/" + leaf + "/versions/" + leafVersion + "/revoke", Map.of("reason", "本次固定版本停止使用"), revision, UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(0, impact(leaf).path("enabledScheduleCount").asInt());
        assertEquals(0, impact(agent).path("enabledScheduleCount").asInt());
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(ScheduleSqlMapper.class).selectCount(new LambdaQueryWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, (enterprise)).eq(ScheduledTaskRow::getEnabled, false).eq(ScheduledTaskRow::getPauseReason, "SCHEDULE_RESOURCE_UNAVAILABLE"))));
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(RunJobSqlMapper.class).selectCount(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, (enterprise)).eq(BackgroundJobRow::getKind, "notification"))));
        assertEquals(0, count("agent_run"));
    }

    @Test
    void disablingAMemberPausesTheirPrivatePlanAndStopsTheAcceptedOccurrence() throws Exception {
        grants.replace(actor(admin), agent, Long.parseLong(resourceRevision()), List.of(new ResourceGrantSpec("enterprise", enterprise, "use")));
        var member = EnterpriseTestData.member(users, permissions, enterprise, "plan_owner_" + UUID.randomUUID().toString().substring(0, 8), "plan-owner-test-password-2026", "计划创建人", List.of(permissions.builtinRoleId(enterprise, "member")));
        var hire = hires.establish(enterprise, member.id(), agent, Instant.now());
        var plan = plans.create(actor(member.id()), request(hire.id(), true));
        var accepted = triggers.manual(actor(member.id()), plan.id(), UUID.randomUUID().toString());
        assertTrue(actionWorker.runOnce());
        accepted = scheduleQueries.get(actor(member.id()), plan.id()).latestOccurrence();
        assertEquals(1, impact(agent).path("enabledScheduleCount").asInt());
        assertEquals(1, reserved());
        mvc.perform(get(base() + "/schedules/" + plan.id()).cookie(cookie)).andExpect(status().isNotFound());
        String revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(IdentityQueryMapper.class).selectList(new LambdaQueryWrapper<EnterpriseMemberRow>().select(EnterpriseMemberRow::getRevision).eq(EnterpriseMemberRow::getEnterpriseId, (enterprise)).eq(EnterpriseMemberRow::getUserId, (member.id()))).stream().map(fixtureRecord -> Objects.toString(fixtureRecord.getRevision(), null)).toList());
        change(HttpMethod.PATCH, base() + "/members/" + member.id() + "/status", Map.of("status", "disabled"), revision, UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(0, impact(agent).path("enabledScheduleCount").asInt());
        assertEquals(0, reserved());
        assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(RunSqlMapper.class).selectList(new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getStatus).eq(AgentRunRow::getId, (accepted.runId()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals("cancelled", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleOccurrenceSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledOccurrenceRow>().select(ScheduledOccurrenceRow::getStatus).eq(ScheduledOccurrenceRow::getId, (accepted.id()))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals("SCHEDULE_OWNER_UNAVAILABLE", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getPauseReason).eq(ScheduledTaskRow::getId, (plan.id()))).stream().map(fixtureRecord -> fixtureRecord.getPauseReason()).toList()));
        assertNull(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getActiveOccurrenceId).eq(ScheduledTaskRow::getId, (plan.id()))).stream().map(fixtureRecord -> fixtureRecord.getActiveOccurrenceId()).toList()));
        assertEquals(List.of(member.id()), databaseAccess.mapper(BackgroundJobFixtureMapper.class).scheduleRevocationApiDisablingAMemberPausesTheirPrivatePlanAndStopsTheAcceptedOccurrenceList(enterprise));
        assertFalse(impact(agent).toString().contains("私有计划正文"));
    }

    @Test
    void disablingTheRootResourceCountsOnlyEnabledPlansAndImmediatelyPausesThem() throws Exception {
        String hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow().id();
        var enabled = plans.create(actor(admin), request(hire, true));
        plans.create(actor(admin), request(hire, false));
        assertEquals(1, impact(agent).path("enabledScheduleCount").asInt());
        change(HttpMethod.PATCH, base() + "/resources/" + agent + "/status", Map.of("status", "disabled"), resourceRevision(), UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(0, impact(agent).path("enabledScheduleCount").asInt());
        assertFalse(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ScheduleSqlMapper.class).selectList(new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getEnabled).eq(ScheduledTaskRow::getId, (enabled.id()))).stream().map(fixtureRecord -> (fixtureRecord.getEnabled() != null && fixtureRecord.getEnabled() != 0)).toList()));
        assertEquals(0, count("agent_run"));
    }

    private AuthContext actor(String user) {
        return new AuthContext(users.findById(user).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
    }

    private ScheduleWriteRequest request(String hire, boolean enabled) {
        return new ScheduleWriteRequest("日常处理", hire, version, "私有计划正文", "daily", null, "09:00", List.of(), null, "UTC", enabled, 0);
    }

    private JsonNode impact(String resource) throws Exception {
        var result = data(mvc.perform(get(base() + "/resources/" + resource + "/impact").cookie(cookie)).andExpect(status().isOk()).andReturn());
        schemas.validate("ResourceImpact", result);
        return result;
    }

    private String create(String kind, Object config) throws Exception {
        return data(write(base() + "/resources", Map.of("kind", kind, "name", "计划引用验收", "description", "真实固定引用", "tagIds", List.of(), "config", config), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private String publish(String resource) throws Exception {
        return data(change(HttpMethod.POST, base() + "/resources/" + resource + "/publish", Map.of("releaseNote", "固定引用"), "1", UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        return Map.of("nodeId", id, "name", id, "type", type, "position", Map.of("x", 0, "y", 0), "timeoutSeconds", 30, "failurePolicy", "stop", "config", config);
    }

    private Map<String, String> edge(String from, String to) {
        return Map.of("edgeId", from + "-" + to, "source", from, "target", to, "branch", "default");
    }
}

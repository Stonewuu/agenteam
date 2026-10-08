package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.agent.request.HireAgentRequest;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.agent.AgentHireService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceLifecycleService;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 删除智能体一次解除所有用户的雇佣，失败、恢复和并发请求不得留下错误关系。
 */
@Import(SharedEnterpriseTestEdition.class)
class AgentDeletionApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @MockitoSpyBean
    private ResourceMapper resources;
    @Autowired
    private ResourceLifecycleService resourceLifecycle;
    @Autowired
    private AgentHireService hireService;
    @Autowired
    private AgentHireSqlMapper hireRows;
    @Autowired
    private ScheduleManagementService plans;
    @Autowired
    private ScheduleMapper schedules;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void deletionTerminatesAllUsersIncludingPausedHiresAndKeepsOtherAgents() throws Exception {
        String pausedUser = member();
        var paused = hires.establish(enterprise, pausedUser, agent, Instant.now());
        hires.changeStatus(paused, "paused", Instant.now());
        String formerUser = member();
        var former = hires.establish(enterprise, formerUser, agent, Instant.now());
        hires.changeStatus(former, "terminated", Instant.now());
        var formerBefore = hireRows.selectById(former.id());
        String other = data(write(base() + "/resources", Map.of("kind", "agent", "name", "保留的智能体",
            "description", "不应受其他智能体删除影响", "tagIds", List.of(),
            "config", resources.find(enterprise, agent, false, false).orElseThrow().config()), UUID.randomUUID().toString())
            .andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
        var otherHire = hires.establish(enterprise, admin, other, Instant.now());
        var plan = plan();
        var impact = data(mvc.perform(get(base() + "/resources/" + agent + "/impact").cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertTrue(impact.path("canDelete").asBoolean());
        assertEquals(1, impact.path("activeHireCount").asInt());
        var mineBefore = data(mvc.perform(get(base() + "/employees").cookie(cookie).param("tab", "mine"))
            .andExpect(status().isOk()).andReturn());
        assertTrue(mineBefore.path("items").findValuesAsText("agentId").contains(agent));

        String revision = resourceRevision();
        String key = UUID.randomUUID().toString();
        change(HttpMethod.DELETE, base() + "/resources/" + agent, null, revision, key).andExpect(status().isOk());
        var after = agentHires();
        assertEquals(3, after.size());
        assertTrue(after.stream().allMatch(value -> value.getStatus().equals("terminated")));
        for (var value : after) {
            assertNotNull(value.getTerminatedAt());
            assertNull(value.getPausedAt());
        }
        assertEquals(2, hireRows.selectById(hires.forAgent(enterprise, admin, agent, false).orElseThrow().id()).getRevision());
        assertEquals(3, hireRows.selectById(paused.id()).getRevision());
        assertEquals(formerBefore.getRevision(), hireRows.selectById(former.id()).getRevision());
        assertEquals(formerBefore.getTerminatedAt(), hireRows.selectById(former.id()).getTerminatedAt());
        assertEquals("active", hireRows.selectById(otherHire.id()).getStatus());
        var stoppedPlan = schedules.find(enterprise, admin, plan, false, false).orElseThrow();
        assertFalse(stoppedPlan.enabled());
        assertNull(stoppedPlan.nextRunAt());
        change(HttpMethod.DELETE, base() + "/resources/" + agent, null, revision, key).andExpect(status().isOk());
        assertEquals(3, hireRows.selectById(paused.id()).getRevision());
        var mine = data(mvc.perform(get(base() + "/employees").cookie(cookie).param("tab", "mine"))
            .andExpect(status().isOk()).andReturn());
        assertTrue(mine.path("items").findValuesAsText("agentId").stream().noneMatch(agent::equals));
        var historical = data(mvc.perform(get(base() + "/employees/" + agent).cookie(cookie))
            .andExpect(status().isOk()).andReturn());
        assertEquals("terminated", historical.path("hireStatus").asText());
        assertFalse(historical.path("canRun").asBoolean());
    }

    @Test
    void restoreAndEnableRequireANewHireAndReuseTheHistoricalRelationship() throws Exception {
        var previous = hires.forAgent(enterprise, admin, agent, false).orElseThrow();
        resourceLifecycle.delete(actor(), agent, Long.parseLong(resourceRevision()));
        var restored = resourceLifecycle.restore(actor(), agent, resources.find(enterprise, agent, true, false).orElseThrow().revision());
        assertEquals("disabled", restored.status());
        assertEquals("terminated", hireRows.selectById(previous.id()).getStatus());
        var enabled = resourceLifecycle.status(actor(), agent, "active", Long.parseLong(restored.revision()));
        assertEquals("terminated", hireRows.selectById(previous.id()).getStatus());
        write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isConflict());
        change(HttpMethod.PUT, base() + "/agents/" + agent + "/listing", Map.of("listed", true, "hirePolicy", "automatic"),
            enabled.revision(), UUID.randomUUID().toString()).andExpect(status().isOk());
        var hired = data(write(base() + "/hires", Map.of("agentId", agent, "note", "重新雇佣"), UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn());
        assertEquals(previous.id(), hired.at("/hire/id").asText());
        assertEquals("active", hired.at("/hire/status").asText());
        assertEquals(previous.revision() + 2, hired.at("/hire/revision").asLong());
    }

    @Test
    void runningTasksStillBlockDeletionWithoutTerminatingHiresOrPausingPlans() throws Exception {
        String plan = plan();
        write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertFalse(resourceLifecycle.impact(actor(), agent).canDelete());
        var error = assertThrows(ApiException.class,
            () -> resourceLifecycle.delete(actor(), agent, Long.parseLong(resourceRevision())));
        assertEquals("RESOURCE_IN_USE", error.code());
        assertTrue(error.getReason().contains("智能体"));
        assertFalse(error.getReason().contains("解除雇佣"));
        assertEquals("active", agentHires().getFirst().getStatus());
        assertTrue(schedules.find(enterprise, admin, plan, false, false).orElseThrow().enabled());
        assertNull(resources.find(enterprise, agent, false, false).orElseThrow().deletedAt());
    }

    @Test
    void failedDeletionRollsBackTerminatedHiresAndPausedPlans() throws Exception {
        String plan = plan();
        var previous = agentHires().getFirst();
        doThrow(new IllegalStateException("测试删除写入失败")).when(resources).markDeleted(any(), any());
        assertThrows(IllegalStateException.class,
            () -> resourceLifecycle.delete(actor(), agent, Long.parseLong(resourceRevision())));
        var after = hireRows.selectById(previous.getId());
        assertEquals("active", after.getStatus());
        assertEquals(previous.getRevision(), after.getRevision());
        assertNull(after.getTerminatedAt());
        assertTrue(schedules.find(enterprise, admin, plan, false, false).orElseThrow().enabled());
        assertNull(resources.find(enterprise, agent, false, false).orElseThrow().deletedAt());
    }

    @Test
    void deniedOrStaleDeletionLeavesHiresUntouched() throws Exception {
        String memberId = member();
        var memberActor = new AuthContext(users.findById(memberId).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(memberId, enterprise)));
        assertThrows(ResponseStatusException.class,
            () -> resourceLifecycle.delete(memberActor, agent, Long.parseLong(resourceRevision())));
        String otherEnterprise = provisioning.create("无关企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        var otherActor = new AuthContext(actor().user(), otherEnterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, otherEnterprise)));
        assertThrows(ResponseStatusException.class,
            () -> resourceLifecycle.delete(otherActor, agent, Long.parseLong(resourceRevision())));
        change(HttpMethod.DELETE, base() + "/resources/" + agent, null, "1", UUID.randomUUID().toString())
            .andExpect(status().isConflict());
        assertEquals("active", agentHires().getFirst().getStatus());
        assertNull(agentHires().getFirst().getTerminatedAt());
    }

    @Test
    void concurrentHiringCannotLeaveAnActiveHireAfterDeletion() throws Exception {
        String user = member();
        change(HttpMethod.PUT, base() + "/resources/" + agent + "/grants", Map.of("grants", List.of(
                Map.of("subjectType", "enterprise", "subjectId", enterprise, "capability", "use"))),
            resourceRevision(), UUID.randomUUID().toString()).andExpect(status().isOk());
        change(HttpMethod.PUT, base() + "/agents/" + agent + "/listing", Map.of("listed", true, "hirePolicy", "automatic"),
            resourceRevision(), UUID.randomUUID().toString()).andExpect(status().isOk());
        var applicant = new AuthContext(users.findById(user).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(user, enterprise)));
        long revision = Long.parseLong(resourceRevision());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var hiring = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    hireService.hire(applicant, new HireAgentRequest(agent, "并发雇佣验证"));
                } catch (ResponseStatusException unavailable) {
                    assertEquals(404, unavailable.getStatusCode().value());
                }
                return null;
            });
            var deletion = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                resourceLifecycle.delete(actor(), agent, revision);
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            hiring.get(15, TimeUnit.SECONDS);
            deletion.get(15, TimeUnit.SECONDS);
        }
        assertTrue(agentHires().stream().allMatch(value -> value.getStatus().equals("terminated")));
        assertNotNull(resources.find(enterprise, agent, true, false).orElseThrow().deletedAt());
    }

    private AuthContext actor() {
        return new AuthContext(users.findById(admin).orElseThrow(), enterprise,
            Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
    }

    private String member() {
        return EnterpriseTestData.member(users, permissions, enterprise, "delete-member-" + UUID.randomUUID(),
            "删除智能体验证所使用的独立完整口令", "删除验证成员",
            List.of(permissions.builtinRoleId(enterprise, "member"))).id();
    }

    private List<AgentHireRow> agentHires() {
        return hireRows.selectList(new LambdaQueryWrapper<AgentHireRow>()
            .eq(AgentHireRow::getEnterpriseId, enterprise).eq(AgentHireRow::getAgentId, agent));
    }

    private String plan() {
        String hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow().id();
        return plans.create(actor(), new ScheduleWriteRequest("删除智能体验证", hire, version, "整理资料", "daily", null,
            "09:00", List.of(), null, "UTC", true, 0)).id();
    }
}

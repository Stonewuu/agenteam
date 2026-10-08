package com.stonewu.agenteam.controller.workspace;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.mapper.execution.RunSqlMapper;
import com.stonewu.agenteam.mapper.workspace.ActivityTrendSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.todo.request.TodoWriteRequest;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import com.stonewu.agenteam.service.todo.TodoManagementService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(SharedEnterpriseTestEdition.class)
class ActivityTrendApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private AuditEventMapper audit;
    @Autowired
    private ActivityTrendSqlMapper activity;
    @Autowired
    private TodoManagementService todos;
    @Autowired
    private ScheduleManagementService schedules;
    @Autowired
    private ScheduleTriggerService triggers;
    @Autowired
    private ScheduleActionWorker actionWorker;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void dailyCountsIncludeInteractiveSubmissionsButExcludeAutomaticOrAmbiguousRunsAndRequestReplays() throws Exception {
        long beforeConversations = sum("conversations");
        long beforeSchedules = sum("schedules");
        String requestKey = UUID.randomUUID().toString();
        var first = data(write(base() + "/conversations", body(), requestKey).andExpect(status().isAccepted()).andReturn());
        write(base() + "/conversations", body(), requestKey).andExpect(status().isAccepted());
        String automatic = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn()).path("runId").asText();
        String manual = data(write(base() + "/conversations", body(), UUID.randomUUID().toString()).andExpect(status().isAccepted()).andReturn()).path("runId").asText();
        assertTrue(!first.path("runId").asText().isBlank());
        var runs = databaseAccess.mapper(RunSqlMapper.class);
        assertEquals(1, runs.update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getId, automatic).set(AgentRunRow::getMode, "scheduled")));
        assertEquals(1, runs.update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getId, manual).set(AgentRunRow::getMode, "manual_schedule")));
        try {
            assertEquals(beforeConversations + 1, sum("conversations"));
            assertEquals(beforeSchedules, sum("schedules"));
        } finally {
            // 这两条仅用于查询分类验证；恢复模式后交给公共测试清理流程停止。
            runs.update(new LambdaUpdateWrapper<AgentRunRow>().in(AgentRunRow::getId, automatic, manual).set(AgentRunRow::getMode, "interactive"));
        }
    }

    @Test
    void scheduledTaskActivityDistinguishesUserRequestsFromEmployeeToolsAndReplayedOccurrences() throws Exception {
        long before = sum("schedules");
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        String hire = hires.forAgent(enterprise, admin, agent, false).orElseThrow().id();
        var input = new ScheduleWriteRequest("人工活动来源测试", hire, version, "整理资料", "daily", null,
            "09:15", List.of(), null, "Asia/Shanghai", false, 0);
        var automatic = schedules.create(actor, input);
        triggers.manual(actor, automatic.id(), "employee-tool-request");
        assertTrue(actionWorker.runOnce());
        assertEquals(before, sum("schedules"));
        lifecycle.stopForUser(enterprise, admin);

        String createKey = UUID.randomUUID().toString();
        var saved = data(write(base() + "/schedules", input, createKey).andExpect(status().isCreated()).andReturn());
        write(base() + "/schedules", input, createKey).andExpect(status().isCreated());
        assertEquals(before + 1, sum("schedules"));
        String id = saved.path("id").asText();
        change(HttpMethod.PATCH, base() + "/schedules/" + id + "/enabled", Map.of("enabled", false), "1", UUID.randomUUID().toString())
            .andExpect(status().isOk());
        assertEquals(before + 1, sum("schedules"));

        String runKey = UUID.randomUUID().toString();
        var accepted = data(write(base() + "/schedules/" + id + "/run", Map.of(), runKey).andExpect(status().isAccepted()).andReturn());
        assertEquals("queued", accepted.path("status").asText());
        assertTrue(accepted.path("runId").isNull());
        assertTrue(actionWorker.runOnce());
        write(base() + "/schedules/" + id + "/run", Map.of(), runKey).andExpect(status().isAccepted());
        assertEquals(before + 2, sum("schedules"));
        lifecycle.stopForUser(enterprise, admin);

        // 即使请求缓存已过期，同一发生记录再次返回也不能追加活动。
        triggers.manual(actor, id, "persisted-user-request", true);
        triggers.manual(actor, id, "persisted-user-request", true);
        assertEquals(before + 3, sum("schedules"));
        assertTrue(actionWorker.runOnce());
        lifecycle.stopForUser(enterprise, admin);
    }

    @Test
    void manualTodoChangesAreAtomicAndDoNotCountAutomaticToolsNoChangesOrFailedRequests() throws Exception {
        long before = sum("todos");
        var value = new TodoWriteRequest("活动统计测试", "仅用于隔离测试", admin, null, null, "normal", "manual", null, null, null);
        var actor = new AuthContext(users.findById(admin).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(admin, enterprise)));
        todos.create(actor, value);
        assertEquals(before, sum("todos"));
        String requestKey = UUID.randomUUID().toString();
        var saved = data(write(base() + "/todos", value, requestKey).andExpect(status().isCreated()).andReturn());
        write(base() + "/todos", value, requestKey).andExpect(status().isCreated());
        assertEquals(before + 1, sum("todos"));
        String path = base() + "/todos/" + saved.path("id").asText() + "/status";
        change(HttpMethod.PATCH, path, Map.of("status", "pending", "reason", ""), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        assertEquals(before + 1, sum("todos"));
        change(HttpMethod.PATCH, path, Map.of("status", "completed", "reason", ""), "1", UUID.randomUUID().toString()).andExpect(status().isOk());
        change(HttpMethod.PATCH, path, Map.of("status", "pending", "reason", ""), "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertEquals(before + 2, sum("todos"));
    }

    @Test
    void aggregationRespectsUserEnterpriseAndFractionalOffsetDateBoundaries() throws Exception {
        Instant midnight = Instant.parse("2026-02-01T18:15:00Z");
        String otherEnterprise = provisioning.create("独立活动企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        for (var time : new Instant[]{midnight.minusSeconds(1), midnight, midnight.plusSeconds(1)}) {
            audit.append(enterprise, admin, "本人", "schedule.create", "schedule", UUID.randomUUID().toString(), "创建计划", "{\"userActivity\":true}", UUID.randomUUID().toString(), time);
        }
        audit.append(otherEnterprise, admin, "本人", "schedule.create", "schedule", "elsewhere", "其他企业", "{\"userActivity\":true}", UUID.randomUUID().toString(), midnight);
        audit.append(enterprise, admin, "本人", "schedule.create", "schedule", "legacy", "来源不明的历史记录", "{}", UUID.randomUUID().toString(), midnight);
        audit.append(enterprise, admin, "本人", "schedule.create", "schedule", "automatic", "员工工具记录", "{\"userActivity\":false}", UUID.randomUUID().toString(), midnight);
        audit.append(enterprise, admin, "本人", "auth.login", "user", admin, "登录", "{}", UUID.randomUUID().toString(), midnight);
        var rows = activity.countActivities(enterprise, admin, midnight.minusSeconds(2), midnight.plusSeconds(2), 20700);
        assertEquals(2, rows.size());
        assertEquals("2026-02-01", rows.getFirst().getDate());
        assertEquals(1, rows.getFirst().getCount());
        assertEquals("2026-02-02", rows.getLast().getDate());
        assertEquals(2, rows.getLast().getCount());
        assertTrue(activity.countActivities(enterprise, "another-user", midnight.minusSeconds(2), midnight.plusSeconds(2), 20700).isEmpty());
        mvc.perform(get(base() + "/home/activity")).andExpect(status().isUnauthorized());
    }

    private long sum(String category) throws Exception {
        JsonNode days = data(mvc.perform(get(base() + "/home/activity").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("days");
        assertTrue(days.size() >= 365 && days.size() <= 366);
        long total = 0;
        for (var day : days) {
            total += day.path(category).asLong();
        }
        return total;
    }
}

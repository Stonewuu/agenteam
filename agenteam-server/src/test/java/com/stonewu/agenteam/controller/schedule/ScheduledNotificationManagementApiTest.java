package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.schedule.ScheduledNotificationTargetMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.ScheduledNotificationTargetRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.service.schedule.ScheduleSelectionService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通知计划通过正式写接口保存，参数和接收关系与智能体操作相互独立。 */
@Import(SharedEnterpriseTestEdition.class)
class ScheduledNotificationManagementApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    @Autowired private ScheduleManagementService management;
    @Autowired private ScheduledNotificationTargetMapper targets;
    @Autowired private ScheduleSelectionService selections;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void notificationPlanSavesWithoutAnAgentAndDoesNotSendAnythingDuringCreation() throws Exception {
        var input = payload(admin);
        String key = UUID.randomUUID().toString();
        var saved = data(write(path(), input, key).andExpect(status().isCreated()).andReturn());
        schemas.validate("Schedule", saved);
        assertEquals(saved, data(write(path(), input, key).andExpect(status().isCreated()).andReturn()));
        assertTrue(saved.path("hireId").isNull());
        assertTrue(saved.path("agentVersionId").isNull());
        assertTrue(saved.path("agentVersionNo").isNull());
        assertEquals("notification.send", saved.at("/action/type").asText());
        assertEquals("参加例会", saved.at("/action/config/title").asText());
        assertEquals(admin, saved.at("/action/recipients/0/userId").asText());
        assertTrue(saved.at("/action/recipients/0/channels").isEmpty());
        String id = saved.path("id").asText();
        var rows = targets.selectList(new LambdaQueryWrapper<ScheduledNotificationTargetRow>()
            .eq(ScheduledNotificationTargetRow::getEnterpriseId, enterprise).eq(ScheduledNotificationTargetRow::getScheduleId, id));
        assertEquals(1, rows.size());
        assertEquals("in_app", rows.getFirst().getChannelKey());
        assertEquals(0, count("agent_run"));
        assertEquals(0, count("notification"));
        assertEquals(0, count("scheduled_occurrence"));
        var page = data(mvc.perform(get(path()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(id, page.at("/items/0/id").asText());
        change(HttpMethod.POST, path() + "/" + id + "/upgrade-version", null, "1", UUID.randomUUID().toString())
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void switchingActionsAtomicallyReplacesAgentFieldsAndRecipientRows() throws Exception {
        var notice = data(write(path(), payload(admin), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String id = notice.path("id").asText();
        var agentInput = payload(admin);
        agentInput.put("action", Map.of("type", "agent.run", "schemaVersion", 1, "config", Map.of(
            "hireId", hires.forAgent(enterprise, admin, agent, false).orElseThrow().id(), "inputText", "整理会议资料")));
        agentInput.put("maxRetries", 2);
        var changed = data(change(HttpMethod.PUT, path() + "/" + id, agentInput, "1", UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn());
        schemas.validate("Schedule", changed);
        assertEquals("agent.run", changed.at("/action/type").asText());
        assertEquals(version, changed.path("agentVersionId").asText());
        assertEquals(0, targets.selectCount(new LambdaQueryWrapper<ScheduledNotificationTargetRow>()
            .eq(ScheduledNotificationTargetRow::getEnterpriseId, enterprise).eq(ScheduledNotificationTargetRow::getScheduleId, id)));
        var restored = data(change(HttpMethod.PUT, path() + "/" + id, payload(admin), changed.path("revision").asText(), UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn());
        assertTrue(restored.path("inputText").isNull());
        assertEquals("notification.send", restored.at("/action/type").asText());
        assertEquals(1, restored.at("/action/recipients").size());
    }

    @Test
    void ordinaryMemberCanPlanSelfNotificationsButCannotSelectAnotherMember() {
        var member = EnterpriseTestData.member(users, permissions, enterprise, "notice_member_" + UUID.randomUUID().toString().substring(0, 8),
            "notice-member-test-password", "通知计划成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var actor = new AuthContext(member, enterprise, Set.copyOf(permissions.listPermissionCodes(member.id(), enterprise)));
        var own = management.create(actor, json.convertValue(payload(member.id()), ScheduleWriteRequest.class));
        assertEquals(List.of(member.id()), selections.recipients(actor, null, null, 20).items().stream().map(value -> value.userId()).toList());
        assertEquals("notification.send", own.action().type());
        var denied = assertThrows(ApiException.class, () -> management.create(actor, json.convertValue(payload(admin), ScheduleWriteRequest.class)));
        assertTrue(denied.fieldErrors().containsKey("action.config.recipients"));
        assertEquals(1, count("scheduled_task"));
        assertEquals(0, count("agent_run"));
    }

    @Test
    void unsupportedVersionsUnknownFieldsAndMixedFormatsAreRejected() throws Exception {
        var mixed = payload(admin);
        mixed.put("inputText", "不应混入通知操作");
        write(path(), mixed, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        var version = payload(admin);
        version.put("action", Map.of("type", "notification.send", "schemaVersion", 99, "config", content(admin)));
        write(path(), version, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        var unknown = new LinkedHashMap<>(content(admin));
        unknown.put("url", "https://example.test/not-supported");
        var extra = payload(admin);
        extra.put("action", Map.of("type", "notification.send", "schemaVersion", 1, "config", unknown));
        write(path(), extra, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        var retry = payload(admin);
        retry.put("maxRetries", 1);
        write(path(), retry, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("scheduled_task"));
    }

    @Test
    void recipientsMustBeUniqueCurrentMembersAndSelectedChannelsMustBeAvailable() throws Exception {
        for (var recipients : List.of(
            List.of(Map.of("userId", admin, "connectionIds", List.of()), Map.of("userId", admin, "connectionIds", List.of())),
            List.of(Map.of("userId", UUID.randomUUID().toString(), "connectionIds", List.of())),
            List.of(Map.of("userId", admin, "connectionIds", List.of(UUID.randomUUID().toString()))))) {
            var content = new LinkedHashMap<>(content(admin));
            content.put("recipients", recipients);
            var input = payload(admin);
            input.put("action", Map.of("type", "notification.send", "schemaVersion", 1, "config", content));
            write(path(), input, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        }
        assertEquals(0, count("scheduled_task"));
    }

    @Test
    void optionEndpointsReturnActualActionSchemasAndScopedRecipients() throws Exception {
        var actions = data(mvc.perform(get(base() + "/schedule-actions").cookie(cookie)).andExpect(status().isOk()).andReturn());
        actions.forEach(item -> schemas.validate("ScheduleActionOption", item));
        assertEquals(2, actions.size());
        assertEquals("agent.run", actions.get(0).path("type").asText());
        assertTrue(actions.get(0).path("usesAgent").asBoolean());
        assertEquals("notification.send", actions.get(1).path("type").asText());
        assertEquals(50, actions.get(1).at("/configSchema/properties/recipients/maxItems").asInt());
        var people = data(mvc.perform(get(base() + "/schedule-recipients").cookie(cookie)).andExpect(status().isOk()).andReturn());
        people.path("items").forEach(item -> schemas.validate("ScheduleRecipientOption", item));
        assertEquals(admin, people.at("/items/0/userId").asText());
        assertTrue(people.at("/items/0/channels").isEmpty());
    }

    private Map<String, Object> payload(String recipient) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "例会通知计划");
        result.put("frequency", "daily");
        result.put("localDate", null);
        result.put("localTime", "09:15");
        result.put("weekdays", List.of());
        result.put("monthDay", null);
        result.put("timezone", "Asia/Shanghai");
        result.put("enabled", false);
        result.put("maxRetries", 0);
        result.put("action", Map.of("type", "notification.send", "schemaVersion", 1, "config", content(recipient)));
        return result;
    }

    private Map<String, Object> content(String recipient) {
        return Map.of("title", "参加例会", "body", "请提前准备会议资料。", "recipients", List.of(Map.of("userId", recipient, "connectionIds", List.of())));
    }

    private String path() {
        return base() + "/schedules";
    }
}

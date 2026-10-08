package com.stonewu.agenteam.controller.schedule;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.mapper.test.schedule.ScheduledOccurrenceFixtureMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.service.schedule.ScheduleManagementService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实固定版本、本人归属、重复请求和数据库约束验证计划管理。
 */
@Import(SharedEnterpriseTestEdition.class)
class ScheduleManagementApiTest extends ExecutionApiTestSupport {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private ScheduleManagementService management;

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
    void savingAndReplayingAPlanDoNotStartAnExecutionAndWeekdayOrderHasNoEffect() throws Exception {
        var input = payload(true);
        input.put("frequency", "weekly");
        input.put("weekdays", List.of(1, 5));
        String key = UUID.randomUUID().toString();
        var created = data(write(path(), input, key).andExpect(status().isCreated()).andReturn());
        schemas.validate("Schedule", created);
        assertEquals(version, created.path("agentVersionId").asText());
        assertEquals(agent, created.path("agentId").asText());
        assertEquals("固定版本的员工", created.path("agentName").asText());
        assertEquals(1, created.path("agentVersionNo").asInt());
        assertTrue(created.path("latestOccurrence").isNull());
        assertTrue(created.path("activeOccurrenceId").isNull());
        input.put("weekdays", List.of(5, 1));
        assertEquals(created, data(write(path(), input, key).andExpect(status().isCreated()).andReturn()));
        input.put("inputText", "同一请求不能换成别的工作");
        write(path(), input, key).andExpect(status().isConflict());
        assertEquals(1, count("scheduled_task"));
        assertEquals(0, count("scheduled_occurrence"));
        assertEquals(0, count("agent_run"));
        assertEquals(0, count("quota_entry"));
        assertEquals(created, detail(created.path("id").asText()));
        var list = data(mvc.perform(get(path() + "?query=每日&limit=1").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(1, list.path("items").size());
        assertTrue(data(mvc.perform(get(path() + "/" + created.path("id").asText() + "/occurrences").cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
    }

    @Test
    void editingKeepsTheFixedVersionUntilTheUserExplicitlyUpgradesIt() throws Exception {
        var input = payload(true);
        var created = create(input);
        String id = created.path("id").asText(), first = created.path("agentVersionId").asText();
        assertEquals("Sparkles", created.path("agentIcon").asText());
        assertEquals("purple", created.path("agentColor").asText());
        var agentDetail = data(mvc.perform(get(base() + "/resources/" + agent).cookie(cookie)).andExpect(status().isOk()).andReturn());
        var nextConfig = ((ObjectNode) agentDetail.path("draft")).deepCopy().put("icon", "NotebookPen").put("color", "mint");
        var update = Map.of("name", "员工第二版", "description", "后续版本", "tagIds", List.of(), "config", nextConfig);
        var saved = data(change(HttpMethod.PUT, base() + "/resources/" + agent + "/draft", update, agentDetail.at("/resource/revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        String second = data(change(HttpMethod.POST, base() + "/resources/" + agent + "/publish", Map.of("releaseNote", "第二版"), saved.at("/resource/revision").asText(), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        assertNotEquals(first, second);
        input.put("inputText", "修改未来任务内容");
        var edited = data(change(HttpMethod.PUT, path() + "/" + id, input, "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertEquals(first, edited.path("agentVersionId").asText());
        assertEquals(1, edited.path("agentVersionNo").asInt());
        assertEquals("Sparkles", edited.path("agentIcon").asText());
        assertEquals("purple", detail(id).path("agentColor").asText());
        var listed = data(mvc.perform(get(path()).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items").get(0);
        assertEquals("Sparkles", listed.path("agentIcon").asText());
        var upgraded = data(change(HttpMethod.POST, path() + "/" + id + "/upgrade-version", null, edited.path("revision").asText(), UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertEquals(second, upgraded.path("agentVersionId").asText());
        assertEquals(2, upgraded.path("agentVersionNo").asInt());
        assertEquals("NotebookPen", upgraded.path("agentIcon").asText());
        assertEquals("mint", upgraded.path("agentColor").asText());
        assertEquals(edited.path("nextRunAt"), upgraded.path("nextRunAt"));
        assertEquals(0, count("agent_run"));
        change(HttpMethod.PUT, path() + "/" + id, input, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
    }

    @Test
    void deletionRequiresPausingAndItsRepeatedRequestRemainsReadable() throws Exception {
        var created = create(payload(true));
        String id = created.path("id").asText();
        change(HttpMethod.DELETE, path() + "/" + id, null, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        var paused = data(change(HttpMethod.PATCH, path() + "/" + id + "/enabled", Map.of("enabled", false), "1", UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn());
        assertFalse(paused.path("enabled").asBoolean());
        assertTrue(paused.path("nextRunAt").isNull());
        String key = UUID.randomUUID().toString();
        change(HttpMethod.DELETE, path() + "/" + id, null, paused.path("revision").asText(), key).andExpect(status().isOk());
        change(HttpMethod.DELETE, path() + "/" + id, null, paused.path("revision").asText(), key).andExpect(status().isOk());
        mvc.perform(get(path() + "/" + id).cookie(cookie)).andExpect(status().isNotFound());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ScheduleSqlMapper.class).selectCount(new LambdaQueryWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getId, (id)).isNotNull(ScheduledTaskRow::getDeletedAt))));
    }

    @Test
    void anotherMembersPlanAndHireStayPrivateEvenFromAnEnterpriseAdministrator() throws Exception {
        var owner = actor(admin);
        long revision = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().select(ResourceRow::getRevision).eq(ResourceRow::getId, (agent))).stream().map(fixtureRecord -> fixtureRecord.getRevision()).toList());
        grants.replace(owner, agent, revision, List.of(new ResourceGrantSpec("enterprise", enterprise, "use")));
        var member = EnterpriseTestData.member(users, permissions, enterprise, "schedule-owner-" + UUID.randomUUID(), "定时任务验收所用的独立口令", "计划创建人", List.of(permissions.builtinRoleId(enterprise, "member")));
        var hire = hires.establish(enterprise, member.id(), agent, Instant.now());
        var request = payload(false);
        request.put("hireId", hire.id());
        var own = management.create(actor(member.id()), json.convertValue(request, ScheduleWriteRequest.class));
        mvc.perform(get(path() + "/" + own.id()).cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get(path() + "/" + own.id() + "/occurrences").cookie(cookie)).andExpect(status().isNotFound());
        change(HttpMethod.PATCH, path() + "/" + own.id() + "/enabled", Map.of("enabled", false), own.revision(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        write(path(), request, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertTrue(data(mvc.perform(get(path()).cookie(cookie)).andExpect(status().isOk()).andReturn()).path("items").isEmpty());
        String other = provisioning.create("另一计划企业", users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        mvc.perform(get("/api/v1/enterprises/" + other + "/schedules/" + own.id()).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void expiredOnceAndInvalidLimitsCannotBeEnabled() throws Exception {
        var once = payload(true);
        once.put("frequency", "once");
        once.put("localDate", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
        write(path(), once, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        once.put("enabled", false);
        var paused = create(once);
        change(HttpMethod.PATCH, path() + "/" + paused.path("id").asText() + "/enabled", Map.of("enabled", true), "1", UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        var invalid = payload(true);
        invalid.put("maxRetries", 3);
        write(path(), invalid, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertEquals(1, count("scheduled_task"));
        assertEquals(0, count("agent_run"));
    }

    @Test
    void databasePreventsRepeatedTimesOverlappingOccurrencesAndWrongActiveReferences() throws Exception {
        String first = create(payload(false)).path("id").asText(), second = create(payload(false)).path("id").asText();
        String occurrence = UUID.randomUUID().toString();
        insertOccurrence(first, occurrence, "same-time", "queued");
        assertThrows(DataIntegrityViolationException.class, () -> insertOccurrence(first, UUID.randomUUID().toString(), "same-time", "missed"));
        assertThrows(DataIntegrityViolationException.class, () -> insertOccurrence(first, UUID.randomUUID().toString(), "other-time", "queued"));
        assertThrows(DataIntegrityViolationException.class, () -> databaseAccess.mapper(ScheduleSqlMapper.class).update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getId, (second)).set(ScheduledTaskRow::getActiveOccurrenceId, (occurrence))));
        databaseAccess.mapper(ScheduleSqlMapper.class).update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getId, (first)).set(ScheduledTaskRow::getActiveOccurrenceId, (occurrence)));
        change(HttpMethod.DELETE, path() + "/" + first, null, "1", UUID.randomUUID().toString()).andExpect(status().isConflict());
        var invalid = assertThrows(DataAccessException.class, () -> databaseAccess.mapper(ScheduleSqlMapper.class).update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getId, (second)).set(ScheduledTaskRow::getEnabled, true).set(ScheduledTaskRow::getNextRunAt, null)));
        assertEquals(3819, ((SQLException) invalid.getMostSpecificCause()).getErrorCode());
    }

    private String path() {
        return base() + "/schedules";
    }

    private AuthContext actor(String id) {
        return new AuthContext(users.findById(id).orElseThrow(), enterprise, Set.copyOf(permissions.listPermissionCodes(id, enterprise)));
    }

    private Map<String, Object> payload(boolean enabled) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "每日整理计划");
        result.put("hireId", hires.forAgent(enterprise, admin, agent, false).orElseThrow().id());
        result.put("agentVersionId", null);
        result.put("inputText", "按当天提供的资料整理工作");
        result.put("frequency", "daily");
        result.put("localDate", null);
        result.put("localTime", "09:15");
        result.put("weekdays", List.of());
        result.put("monthDay", null);
        result.put("timezone", "Asia/Shanghai");
        result.put("enabled", enabled);
        result.put("maxRetries", 2);
        return result;
    }

    private JsonNode create(Map<String, Object> input) throws Exception {
        return data(write(path(), input, UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode detail(String id) throws Exception {
        return data(mvc.perform(get(path() + "/" + id).cookie(cookie)).andExpect(status().isOk()).andReturn());
    }

    private void insertOccurrence(String schedule, String id, String key, String status) {
        databaseAccess.mapper(ScheduledOccurrenceFixtureMapper.class).scheduleManagementApiInsertOccurrenceUpdate(id, enterprise, schedule, key, Timestamp.from(Instant.now()), status);
    }
}

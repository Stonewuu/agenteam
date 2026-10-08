package com.stonewu.agenteam.controller.agent;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentHireApplicationSqlMapper;
import com.stonewu.agenteam.mapper.agent.AgentHireSqlMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRequestRow;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.service.agent.AgentHireService;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import com.stonewu.agenteam.support.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 雇佣请求验证当前权限与真实关系，审批和撤回必须竞争同一终态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class AgentHireApiTest {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final String PASSWORD = "员工雇佣接口所使用的独立完整测试口令";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MybatisTestDatabase databaseAccess;

    @Autowired
    private AuthMapper users;

    @Autowired
    private PermissionMapper permissions;

    @Autowired
    private EnterpriseProvisioningService provisioning;

    @Autowired
    private ModelProfileTestData models;

    private final ApiContractAssertions schemas = new ApiContractAssertions();

    @Autowired
    private AgentHireService hireService;

    @Autowired
    private NotificationDeliveryService notifications;

    private SessionToken admin;

    private SessionToken member;

    private String adminId;

    private String enterprise;

    private String model;

    private record SessionToken(Cookie cookie, String token, String userId) {
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @BeforeAll
    void login() throws Exception {
        var response = write(HttpMethod.POST, "/api/v1/auth/bootstrap", csrf(null, null), null, UUID.randomUUID().toString(), Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "hire-api-admin", "displayName", "员工管理员", "password", PASSWORD, "enterpriseName", "雇佣测试基础企业", "email", "hire-admin@example.test", "timezone", "Asia/Shanghai")).andExpect(status().isCreated()).andReturn();
        adminId = data(response).path("id").asText();
        admin = csrf(response.getResponse().getCookie("SESSION"), adminId);
    }

    @AfterAll
    void close() throws Exception {
        ENVIRONMENT.close();
    }

    @BeforeEach
    void enterprise() throws Exception {
        enterprise = provisioning.create("员工接口测试企业", users.findById(adminId).orElseThrow(), Instant.now()).enterpriseId();
        model = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, adminId, "hire-test-model", 1, "雇佣测试模型", "openai", "test-model", "http://127.0.0.1:1/v1", null, new ModelCapabilities(true, true, 8192, 32768, List.of("text")), true)), key -> null).getFirst();
        member = member();
    }

    @Test
    void repeatedHireAndPersonalStatusChangesReuseOneRelationshipAndUnlistingOnlyPreventsNewHires() throws Exception {
        String agent = employee("automatic");
        var hired = data(hire(agent, member).andExpect(status().isOk()).andReturn());
        schemas.validate("HireResult", hired);
        String id = hired.at("/hire/id").asText();
        int audits = Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, enterprise)));
        assertEquals(hired, data(hire(agent, member).andExpect(status().isOk()).andReturn()));
        assertEquals(audits, Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, enterprise))));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise))));
        var another = member();
        write(HttpMethod.PATCH, base() + "/hires/" + id, another, "1", UUID.randomUUID().toString(), Map.of("status", "paused")).andExpect(status().isNotFound());
        write(HttpMethod.PATCH, base() + "/hires/" + id, member, "1", UUID.randomUUID().toString(), Map.of("status", "paused")).andExpect(status().isOk());
        write(HttpMethod.PUT, base() + "/agents/" + agent + "/listing", admin, "2", UUID.randomUUID().toString(), Map.of("listed", false, "hirePolicy", "automatic")).andExpect(status().isOk());
        assertTrue(info(agent, member).path("canResume").asBoolean());
        var resumed = data(write(HttpMethod.PATCH, base() + "/hires/" + id, member, "2", UUID.randomUUID().toString(), Map.of("status", "active")).andExpect(status().isOk()).andReturn());
        assertEquals(id, resumed.path("id").asText());
        hire(agent, another).andExpect(status().isNotFound());
        write(HttpMethod.PATCH, base() + "/hires/" + id, member, "3", UUID.randomUUID().toString(), Map.of("status", "terminated")).andExpect(status().isOk());
        write(HttpMethod.PATCH, base() + "/hires/" + id, member, "4", UUID.randomUUID().toString(), Map.of("status", "active")).andExpect(status().isConflict());
        write(HttpMethod.PUT, base() + "/agents/" + agent + "/listing", admin, "3", UUID.randomUUID().toString(), Map.of("listed", true, "hirePolicy", "automatic")).andExpect(status().isOk());
        var hiredAgain = data(hire(agent, member).andExpect(status().isOk()).andReturn());
        assertEquals(id, hiredAgain.at("/hire/id").asText());
        assertEquals("5", hiredAgain.at("/hire/revision").asText());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise))));
    }

    @Test
    void approvalAndWithdrawalHaveOneTerminalOutcomeAndCannotProduceDuplicateHires() throws Exception {
        String agent = employee("approval");
        var pending = data(hire(agent, member).andExpect(status().isOk()).andReturn());
        schemas.validate("HireResult", pending);
        assertEquals(pending, data(hire(agent, member).andExpect(status().isOk()).andReturn()));
        String id = pending.at("/application/id").asText();
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise))));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approval = executor.submit(() -> concurrentDecision(id, true, ready, start));
            var withdrawal = executor.submit(() -> concurrentDecision(id, false, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(200, 409), Set.of(approval.get(15, TimeUnit.SECONDS), withdrawal.get(15, TimeUnit.SECONDS)));
        }
        String finalStatus = DataAccessUtils.nullableSingleResult(databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectList(new LambdaQueryWrapper<AgentHireRequestRow>().select(AgentHireRequestRow::getStatus).eq(AgentHireRequestRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList());
        assertTrue(Set.of("approved", "withdrawn").contains(finalStatus));
        assertEquals(finalStatus.equals("approved") ? 1 : 0, Math.toIntExact(databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getId, id).isNotNull(AgentHireRequestRow::getPendingMarker))));
    }

    @Test
    void approvalRechecksUseGrantsAndExpiredApplicationsCannotBeApproved() throws Exception {
        String agent = employee("approval");
        String id = data(hire(agent, member).andExpect(status().isOk()).andReturn()).at("/application/id").asText();
        write(HttpMethod.PUT, base() + "/resources/" + agent + "/grants", admin, "2", UUID.randomUUID().toString(), Map.of("grants", List.of())).andExpect(status().isOk());
        String decisionKey = UUID.randomUUID().toString();
        decide(id, "approve", null, decisionKey).andExpect(status().isNotFound());
        assertEquals("pending", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectList(new LambdaQueryWrapper<AgentHireRequestRow>().select(AgentHireRequestRow::getStatus).eq(AgentHireRequestRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise))));
        write(HttpMethod.PUT, base() + "/resources/" + agent + "/grants", admin, "3", UUID.randomUUID().toString(), Map.of("grants", useGrant())).andExpect(status().isOk());
        databaseAccess.mapper(AgentHireApplicationSqlMapper.class).update(new LambdaUpdateWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getId, (id)).set(AgentHireRequestRow::getExpiresAt, (Timestamp.from(Instant.now().minusSeconds(1)))));
        decide(id, "approve", null, decisionKey).andExpect(status().isConflict());
        assertEquals(1, hireService.expireApplications());
        assertEquals("expired", DataAccessUtils.nullableSingleResult(databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectList(new LambdaQueryWrapper<AgentHireRequestRow>().select(AgentHireRequestRow::getStatus).eq(AgentHireRequestRow::getId, (id))).stream().map(fixtureRecord -> fixtureRecord.getStatus()).toList()));
        var next = data(hire(agent, member).andExpect(status().isOk()).andReturn());
        assertNotEquals(id, next.at("/application/id").asText());
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getEnterpriseId, enterprise))));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(AgentHireApplicationSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getEnterpriseId, enterprise).eq(AgentHireRequestRow::getPendingMarker, 1))));
    }

    @Test
    void rejectionNeedsAReasonAndAnApprovedRequestIsNotRepeated() throws Exception {
        String agent = employee("approval");
        String id = data(hire(agent, member).andExpect(status().isOk()).andReturn()).at("/application/id").asText();
        decide(id, "reject", null, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        decide(id, "reject", "请补充使用场景后重新申请。", UUID.randomUUID().toString()).andExpect(status().isOk());
        String second = data(hire(agent, member).andExpect(status().isOk()).andReturn()).at("/application/id").asText();
        String key = UUID.randomUUID().toString();
        var approved = data(decide(second, "approve", "", key).andExpect(status().isOk()).andReturn());
        schemas.validate("HireApplication", approved);
        assertEquals("Telescope", approved.path("agentIcon").asText());
        assertEquals("blue", approved.path("agentColor").asText());
        assertEquals(approved, data(decide(second, "approve", "", key).andExpect(status().isOk()).andReturn()));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise))));
        for (var candidate : notifications.candidates().stream().filter(value -> value.enterprise().equals(enterprise)).toList()) {
            notifications.deliver(candidate);
            notifications.deliver(candidate);
        }
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(NotificationSqlMapper.class).selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, (enterprise)).eq(NotificationRow::getUserId, (member.userId())))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(NotificationSqlMapper.class).selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, (enterprise)).eq(NotificationRow::getUserId, (adminId)))));
        var rejected = data(mvc.perform(get(base() + "/hire-requests/" + id).cookie(member.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("HireApplication", rejected);
        assertEquals("rejected", rejected.path("status").asText());
        assertEquals("请补充使用场景后重新申请。", rejected.path("decisionNote").asText());
        var outsider = member();
        mvc.perform(get(base() + "/hire-requests/" + id).cookie(outsider.cookie())).andExpect(status().isNotFound());
        assertEquals(approved, data(mvc.perform(get(base() + "/hire-requests/" + second).cookie(member.cookie())).andExpect(status().isOk()).andReturn()));
        assertEquals(2, Math.toIntExact(databaseAccess.mapper(NotificationSqlMapper.class).selectCount(new LambdaQueryWrapper<NotificationRow>().eq(NotificationRow::getEnterpriseId, enterprise).isNull(NotificationRow::getReadAt))));
    }

    private int concurrentDecision(String id, boolean approval, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("审批并发测试未开始");
        }
        return (approval ? decide(id, "approve", null, UUID.randomUUID().toString()) : write(HttpMethod.POST, base() + "/hire-requests/" + id + "/withdraw", member, "1", UUID.randomUUID().toString(), null)).andReturn().getResponse().getStatus();
    }

    @Test
    void applicationListsFilterPersonalAndApprovalScopesBeforePagination() throws Exception {
        String agent = employee("approval");
        hire(agent, member).andExpect(status().isOk());
        var another = member();
        hire(agent, another).andExpect(status().isOk());
        var own = data(mvc.perform(get(base() + "/hire-requests").cookie(member.cookie()).param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertEquals(1, own.path("items").size());
        assertEquals(member.userId(), own.at("/items/0/applicant/id").asText());
        assertEquals(json.valueToTree(List.of("withdraw")), own.at("/items/0/allowedActions"));
        assertEquals(false, own.path("hasMore").asBoolean());
        var all = data(mvc.perform(get(base() + "/hire-requests").cookie(admin.cookie()).param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertTrue(all.path("hasMore").asBoolean());
        mvc.perform(get(base() + "/hire-requests").cookie(member.cookie()).param("cursor", all.path("nextCursor").asText())).andExpect(status().isBadRequest());
        String roleId = UUID.randomUUID().toString();
        permissions.insertRole(roleId, enterprise, "own-reviewer", "本人资源审批", "", DataScope.OWN, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, roleId, Set.of("agent.edit", "agent.hire_approve"));
        String username = "reviewer-" + UUID.randomUUID();
        var reviewer = EnterpriseTestData.member(users, permissions, enterprise, username, PASSWORD, "资源审批人", List.of(roleId));
        var login = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null, null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", PASSWORD)).andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("SESSION");
        var inaccessible = data(mvc.perform(get(base() + "/hire-requests").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertTrue(inaccessible.path("items").isEmpty());
        write(HttpMethod.PUT, base() + "/resources/" + agent + "/owner", admin, "2", UUID.randomUUID().toString(), Map.of("ownerUserId", reviewer.id())).andExpect(status().isOk());
        var assigned = data(mvc.perform(get(base() + "/hire-requests").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(2, assigned.path("items").size());
        assertEquals(json.valueToTree(List.of("approve", "reject")), assigned.at("/items/0/allowedActions"));
        schemas.validate("HireApplication", assigned.at("/items/0"));
    }

    private ResultActions decide(String id, String decision, String reason, String key) throws Exception {
        var body = json.createObjectNode().put("decision", decision);
        if (reason != null) {
            body.put("reason", reason);
        }
        return write(HttpMethod.POST, base() + "/hire-requests/" + id + "/decision", admin, "1", key, body);
    }

    @Test
    void marketplaceUsesPublishedInformationAndPersonalRelationsRemainVisibleAfterAccessChanges() throws Exception {
        String agent = employee("automatic");
        var before = info(agent, member);
        schemas.validate("Employee", before);
        assertTrue(before.path("canHire").asBoolean());
        assertFalse(before.path("canRun").asBoolean());
        assertFalse(before.toString().contains("instructions"));
        assertFalse(before.toString().contains("modelProfileId"));
        hire(agent, member).andExpect(status().isOk());
        assertTrue(info(agent, member).path("canRun").asBoolean());
        write(HttpMethod.PUT, base() + "/agents/" + agent + "/listing", admin, "2", UUID.randomUUID().toString(), Map.of("listed", false, "hirePolicy", "automatic")).andExpect(status().isOk());
        var market = data(mvc.perform(get(base() + "/employees").cookie(member.cookie()).param("tab", "market")).andExpect(status().isOk()).andReturn());
        assertTrue(market.path("items").isEmpty());
        var mine = data(mvc.perform(get(base() + "/employees").cookie(member.cookie()).param("tab", "mine")).andExpect(status().isOk()).andReturn());
        assertEquals(1, mine.path("items").size());
        assertTrue(mine.at("/items/0/canRun").asBoolean());
        var stranger = member();
        mvc.perform(get(base() + "/employees/" + agent).cookie(stranger.cookie())).andExpect(status().isNotFound());
        write(HttpMethod.PUT, base() + "/resources/" + agent + "/grants", admin, "3", UUID.randomUUID().toString(), Map.of("grants", List.of())).andExpect(status().isOk());
        var unavailable = info(agent, member);
        assertEquals("active", unavailable.path("hireStatus").asText());
        assertFalse(unavailable.path("canRun").asBoolean());
        assertFalse(unavailable.path("unavailableReason").isNull());
    }

    @Test
    void pendingEmployeeExposesOnlyTheCallersApplicationAndUnavailableModelsPreventNewHires() throws Exception {
        String agent = employee("approval");
        String application = data(hire(agent, member).andExpect(status().isOk()).andReturn()).at("/application/id").asText();
        var pending = info(agent, member);
        assertEquals("pending", pending.path("hireStatus").asText());
        assertEquals(application, pending.path("applicationId").asText());
        assertFalse(pending.path("canHire").asBoolean());
        var another = member();
        assertTrue(info(agent, another).path("applicationId").isNull());
        models.saveProfiles(List.of(new ModelProfileFixture(enterprise, adminId, "hire-test-model", 1, "雇佣测试模型", "openai", "test-model", "http://127.0.0.1:1/v1", null, new ModelCapabilities(true, true, 8192, 32768, List.of("text")), false)), name -> null);
        var disabled = info(agent, another);
        assertFalse(disabled.path("canHire").asBoolean());
        assertFalse(disabled.path("canRun").asBoolean());
        hire(agent, another).andExpect(status().isConflict());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(AgentHireSqlMapper.class).selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise))));
    }

    @Test
    void employeeSkillsRequireTheirOwnUseGrantAndTagsFilterBeforePaging() throws Exception {
        var skillConfig = Map.of("icon", "BookOpen", "color", "purple", "scenario", "", "inputDescription", "", "instructions", "只有配置维护者可以读取的技能指令。", "outputDescription", "", "example", "", "pluginVersionIds", List.of(), "knowledgeVersionIds", List.of(), "showInWorkspace", false);
        var skill = data(write(HttpMethod.POST, base() + "/resources", admin, null, UUID.randomUUID().toString(), Map.of("kind", "skill", "name", "独立授权技能", "description", "技能公开介绍", "tagIds", List.of(), "config", skillConfig)).andExpect(status().isCreated()).andReturn());
        String skillId = skill.at("/resource/id").asText();
        String skillVersion = data(write(HttpMethod.POST, base() + "/resources/" + skillId + "/publish", admin, "1", UUID.randomUUID().toString(), Map.of("releaseNote", "首次发布")).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String agent = employee("automatic", List.of(skillVersion));
        assertTrue(info(agent, member).path("skills").isEmpty());
        write(HttpMethod.PUT, base() + "/resources/" + skillId + "/grants", admin, "2", UUID.randomUUID().toString(), Map.of("grants", useGrant())).andExpect(status().isOk());
        var disclosed = info(agent, member);
        assertEquals(skillId, disclosed.at("/skills/0/id").asText());
        assertFalse(disclosed.toString().contains("只有配置维护者"));
        var tag = data(write(HttpMethod.POST, base() + "/tags", admin, null, UUID.randomUUID().toString(), Map.of("name", "资料整理")).andExpect(status().isCreated()).andReturn());
        String tagId = tag.path("id").asText();
        var detail = data(mvc.perform(get(base() + "/resources/" + agent).cookie(admin.cookie())).andExpect(status().isOk()).andReturn());
        write(HttpMethod.PUT, base() + "/resources/" + agent + "/draft", admin, "2", UUID.randomUUID().toString(), Map.of("name", "尚未发布的名称", "description", "未发布的介绍", "tagIds", List.of(tagId), "config", detail.path("draft"))).andExpect(status().isOk());
        employee("automatic");
        var filtered = data(mvc.perform(get(base() + "/employees").cookie(member.cookie()).param("tagIds", tagId).param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertEquals(agent, filtered.at("/items/0/agentId").asText());
        assertEquals("雇佣测试员工", filtered.at("/items/0/name").asText());
        assertEquals("资料整理", filtered.at("/items/0/tags/0").asText());
        assertFalse(filtered.path("hasMore").asBoolean());
    }

    private JsonNode info(String id, SessionToken actor) throws Exception {
        return data(mvc.perform(get(base() + "/employees/" + id).cookie(actor.cookie())).andExpect(status().isOk()).andReturn());
    }

    @Test
    void disablingPausesActualHiresAndDeletionRestoresWithoutRestartingOrRelisting() throws Exception {
        String agent = employee("automatic");
        hire(agent, member).andExpect(status().isOk());
        assertEquals("1", info(agent, member).path("hireRevision").asText());
        var impact = data(mvc.perform(get(base() + "/resources/" + agent + "/impact").cookie(admin.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("ResourceImpact", impact);
        assertEquals(1, impact.path("activeHireCount").asInt());
        assertTrue(impact.path("canDelete").asBoolean());
        assertEquals(0, impact.path("activeRunCount").asInt());
        assertEquals(0, impact.path("enabledScheduleCount").asInt());
        write(HttpMethod.PATCH, base() + "/resources/" + agent + "/status", admin, "2", UUID.randomUUID().toString(), Map.of("status", "disabled")).andExpect(status().isOk());
        var paused = info(agent, member);
        assertEquals("paused", paused.path("hireStatus").asText());
        assertEquals("2", paused.path("hireRevision").asText());
        assertFalse(paused.path("canRun").asBoolean());
        assertFalse(paused.path("canResume").asBoolean());
        String key = UUID.randomUUID().toString();
        write(HttpMethod.DELETE, base() + "/resources/" + agent, admin, "3", key, null).andExpect(status().isOk());
        write(HttpMethod.DELETE, base() + "/resources/" + agent, admin, "3", key, null).andExpect(status().isOk());
        mvc.perform(get(base() + "/resources/" + agent).cookie(admin.cookie())).andExpect(status().isNotFound());
        var restored = data(write(HttpMethod.POST, base() + "/resources/" + agent + "/restore", admin, "4", UUID.randomUUID().toString(), null).andExpect(status().isOk()).andReturn());
        assertEquals("disabled", restored.path("status").asText());
        assertFalse(restored.at("/listing/listed").asBoolean());
        assertEquals("terminated", databaseAccess.mapper(AgentHireSqlMapper.class).selectOne(new LambdaQueryWrapper<AgentHireRow>()
            .eq(AgentHireRow::getEnterpriseId, enterprise).eq(AgentHireRow::getAgentId, agent)
            .eq(AgentHireRow::getUserId, member.userId())).getStatus());
        write(HttpMethod.DELETE, base() + "/resources/" + agent, admin, "5", UUID.randomUUID().toString(), null).andExpect(status().isOk());
        databaseAccess.mapper(ResourceSqlMapper.class).update(new LambdaUpdateWrapper<ResourceRow>().eq(ResourceRow::getId, (agent)).set(ResourceRow::getDeletedAt, (Timestamp.from(Instant.now().minusSeconds(31L * 86400)))));
        write(HttpMethod.POST, base() + "/resources/" + agent + "/restore", admin, "6", UUID.randomUUID().toString(), null).andExpect(status().isNotFound());
        var deleted = data(mvc.perform(get(base() + "/resources").cookie(admin.cookie()).param("kind", "agent").param("status", "deleted")).andExpect(status().isOk()).andReturn());
        assertTrue(deleted.path("items").isEmpty());
    }

    private ResultActions hire(String agent, SessionToken actor) throws Exception {
        return write(HttpMethod.POST, base() + "/hires", actor, null, UUID.randomUUID().toString(), Map.of("agentId", agent, "note", "整理日常资料。"));
    }

    private String employee(String policy) throws Exception {
        return employee(policy, List.of());
    }

    private String employee(String policy, List<String> skills) throws Exception {
        var configuration = (ObjectNode) json.readTree("""
            {"icon":"Sparkles","color":"purple","agentType":"chat","businessRole":"资料整理","modelProfileId":null,
             "instructions":"仅整理用户提供的信息。","maxSteps":20,"timeoutSeconds":120,
             "attachmentsEnabled":false,"welcomeMessage":"","suggestedQuestions":[],"skillVersionIds":[],"pluginVersionIds":[],
             "knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],"entryWorkflowVersionId":null,"historyMessageLimit":20,
             "memoryEnabled":false,"memoryFields":[],"businessTerms":[],"researchSubagentEnabled":false,"publicExamples":[]}
            """);
        configuration.put("modelProfileId", model).put("icon", "Telescope").put("color", "blue");
        configuration.set("skillVersionIds", json.valueToTree(skills));
        var created = data(write(HttpMethod.POST, base() + "/resources", admin, null, UUID.randomUUID().toString(), Map.of("kind", "agent", "name", "雇佣测试员工", "description", "整理公开输入的资料", "tagIds", List.of(), "config", configuration)).andExpect(status().isCreated()).andReturn());
        String id = created.at("/resource/id").asText();
        write(HttpMethod.POST, base() + "/resources/" + id + "/publish", admin, "1", UUID.randomUUID().toString(), Map.of("releaseNote", "首次发布", "grants", useGrant(), "listing", Map.of("listed", true, "hirePolicy", policy))).andExpect(status().isCreated());
        return id;
    }

    private List<Map<String, String>> useGrant() {
        return List.of(Map.of("subjectType", "enterprise", "subjectId", enterprise, "capability", "use"));
    }

    private SessionToken member() throws Exception {
        String username = "hire-member-" + UUID.randomUUID();
        var user = EnterpriseTestData.member(users, permissions, enterprise, username, PASSWORD, "雇佣成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var loggedIn = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null, null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", PASSWORD)).andExpect(status().isOk()).andReturn();
        return csrf(loggedIn.getResponse().getCookie("SESSION"), user.id());
    }

    private SessionToken csrf(Cookie cookie, String userId) throws Exception {
        var value = get("/api/v1/auth/csrf");
        if (cookie != null) {
            value.cookie(cookie);
        }
        var response = mvc.perform(value).andExpect(status().isOk()).andReturn();
        Cookie next = response.getResponse().getCookie("SESSION");
        if (next == null) {
            next = cookie;
        }
        assertNotNull(next);
        return new SessionToken(next, data(response).path("token").asText(), userId);
    }

    private ResultActions write(HttpMethod method, String path, SessionToken session, String revision, String key, Object body) throws Exception {
        var value = request(method, path).cookie(session.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", session.token()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            value.content(json.writeValueAsString(body));
        }
        if (revision != null) {
            value.header("If-Match", "\"" + revision + "\"");
        }
        return mvc.perform(value);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String base() {
        return "/api/v1/enterprises/" + enterprise;
    }
}

package com.stonewu.agenteam.controller.resource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.audit.AuditEventSqlMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.http.ApiRequestMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDependencyTableMapper;
import com.stonewu.agenteam.mapper.resource.ResourceSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.agent.entity.AgentListingRow;
import com.stonewu.agenteam.model.audit.entity.AuditEventRow;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiRequestRow;
import com.stonewu.agenteam.model.modelprofile.entity.ModelCapabilities;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantRow;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceDependencyRow;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.support.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实网页请求验证发布、授权、上架及重复请求的同一数据库事务。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedEnterpriseTestEdition.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourcePublicationApiTest {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final String PASSWORD = "资源发布流程所使用的独立完整测试口令";

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
    private ResourceVersionMapper versions;

    @Autowired
    private ResourceGrantService grants;

    private SessionToken admin;

    private String adminId;

    private String enterprise;

    private String model;

    private record SessionToken(Cookie cookie, String token) {
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @BeforeAll
    void login() throws Exception {
        var response = write(HttpMethod.POST, "/api/v1/auth/bootstrap", csrf(null), null, UUID.randomUUID().toString(), Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "resource-api-admin", "displayName", "资源管理员", "password", PASSWORD, "enterpriseName", "资源测试基础企业", "email", "resource-admin@example.test", "timezone", "Asia/Shanghai")).andExpect(status().isCreated()).andReturn();
        adminId = data(response).path("id").asText();
        admin = csrf(response.getResponse().getCookie("SESSION"));
    }

    @AfterAll
    void close() throws Exception {
        ENVIRONMENT.close();
    }

    @BeforeEach
    void enterprise() {
        enterprise = provisioning.create("资源接口测试企业", users.findById(adminId).orElseThrow(), Instant.now()).enterpriseId();
        model = models.saveProfiles(List.of(new ModelProfileFixture(enterprise, adminId, "resource-model", 1, "测试配置", "openai", "test-model", "http://127.0.0.1:1/v1", "TEST_MODEL_KEY", new ModelCapabilities(true, true, 8192, 32768, List.of("text")), true)), key -> "isolated-model-test-key").getFirst();
    }

    @Test
    void concurrentRepeatedPublicationCreatesOneFixedVersionAndCommitsListingAndGrantsTogether() throws Exception {
        var skill = create("skill", "信息整理技能", skill());
        String skillId = skill.at("/resource/id").asText();
        String skillVersion = data(publish(skillId, "1", Map.of("releaseNote", "首次发布"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        var configuration = agent();
        configuration.putArray("skillVersionIds").add(skillVersion);
        var created = create("agent", "资料助手", configuration);
        String id = created.at("/resource/id").asText();
        assertFalse(created.at("/resource/listing/listed").asBoolean());
        String key = UUID.randomUUID().toString();
        var body = Map.of("releaseNote", "固定首版配置", "grants", List.of(Map.of("subjectType", "enterprise", "subjectId", enterprise, "capability", "use")), "listing", Map.of("listed", true, "hirePolicy", "approval"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        JsonNode published;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentPublish(id, key, body, ready, start));
            var second = executor.submit(() -> concurrentPublish(id, key, body, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            published = first.get(15, TimeUnit.SECONDS);
            assertEquals(published, second.get(15, TimeUnit.SECONDS));
        }
        schemas.validate("ResourceVersion", published);
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, id))));
        assertEquals(2, Math.toIntExact(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getId, id).select(ResourceRow::getRevision)).stream().map(value -> value.getRevision()).toList())));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ResourceAuthorizationSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceGrantRow>().eq(ResourceGrantRow::getResourceId, id))));
        assertEquals(skillVersion, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDependencyTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDependencyRow>().select(ResourceDependencyRow::getDependencyVersionId).eq(ResourceDependencyRow::getParentVersionId, (published.at("/version/id").asText()))).stream().map(fixtureRecord -> fixtureRecord.getDependencyVersionId()).toList()));
        var detail = detail(id);
        assertTrue(detail.at("/resource/listing/listed").asBoolean());
        assertEquals("approval", detail.at("/resource/listing/hirePolicy").asText());
        assertFalse(detail.at("/resource/hasUnpublishedChanges").asBoolean());
        configuration.put("instructions", "草稿修改后的新指令");
        write(HttpMethod.PUT, resource(id) + "/draft", admin, "2", UUID.randomUUID().toString(), Map.of("name", "资料助手", "description", "根据已授权资料完成整理", "tagIds", List.of(), "config", configuration)).andExpect(status().isOk());
        assertTrue(detail(id).at("/resource/hasUnpublishedChanges").asBoolean());
        var fixed = data(mvc.perform(get(resource(id) + "/versions/" + published.at("/version/id").asText()).cookie(admin.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals("先确认输入，再整理可用信息。", fixed.at("/config/instructions").asText());
        write(HttpMethod.PUT, base() + "/agents/" + id + "/listing", admin, "3", UUID.randomUUID().toString(), Map.of("listed", false, "hirePolicy", "automatic")).andExpect(status().isOk());
        assertFalse(detail(id).at("/resource/listing/listed").asBoolean());
        assertEquals(published.at("/version/id").asText(), detail(id).at("/resource/publishedVersion/id").asText());
    }

    @Test
    void rejectedGrantOrListingLeavesNoVersionGrantAuditOrCompletedRequest() throws Exception {
        var created = create("agent", "权限失败测试", agent());
        String id = created.at("/resource/id").asText();
        int audits = Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, enterprise)));
        int requests = Math.toIntExact(databaseAccess.mapper(ApiRequestMapper.class).selectCount(new LambdaQueryWrapper<ApiRequestRow>().eq(ApiRequestRow::getEnterpriseId, enterprise)));
        publish(id, "1", Map.of("releaseNote", "不应发布", "grants", List.of(Map.of("subjectType", "user", "subjectId", "missing-member", "capability", "use")), "listing", Map.of("listed", true, "hirePolicy", "automatic")), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertUnpublished(id);
        assertEquals(audits, Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, enterprise))));
        assertEquals(requests, Math.toIntExact(databaseAccess.mapper(ApiRequestMapper.class).selectCount(new LambdaQueryWrapper<ApiRequestRow>().eq(ApiRequestRow::getEnterpriseId, enterprise))));
        var skill = create("skill", "不能上架的技能", skill());
        String skillId = skill.at("/resource/id").asText();
        audits = Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, enterprise)));
        publish(skillId, "1", Map.of("releaseNote", "整体撤销", "grants", List.of(Map.of("subjectType", "enterprise", "subjectId", enterprise, "capability", "use")), "listing", Map.of("listed", true, "hirePolicy", "automatic")), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertUnpublished(skillId);
        assertEquals(audits, Math.toIntExact(databaseAccess.mapper(AuditEventSqlMapper.class).selectCount(new LambdaQueryWrapper<AuditEventRow>().eq(AuditEventRow::getEnterpriseId, enterprise))));
        var nullGrants = json.createObjectNode().put("releaseNote", "不可把空值当作省略").putNull("grants");
        publish(id, "1", nullGrants, UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        assertUnpublished(id);
    }

    @Test
    void resourceUseDoesNotRevealDraftsAndForeignVersionsCannotBeReadOrPublished() throws Exception {
        String id = create("agent", "私有配置", agent()).at("/resource/id").asText();
        String version = data(publish(id, "1", Map.of("releaseNote", "允许成员使用", "grants", List.of(Map.of("subjectType", "enterprise", "subjectId", enterprise, "capability", "use"))), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String username = "member-" + UUID.randomUUID();
        EnterpriseTestData.member(users, permissions, enterprise, username, PASSWORD, "普通成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var login = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", PASSWORD)).andExpect(status().isOk()).andReturn();
        Cookie member = login.getResponse().getCookie("SESSION");
        mvc.perform(get(resource(id)).cookie(member)).andExpect(status().isForbidden());
        mvc.perform(get(resource(id) + "/versions/" + version).cookie(member)).andExpect(status().isForbidden());
        String other = provisioning.create("另一个资源企业", users.findById(adminId).orElseThrow(), Instant.now()).enterpriseId();
        String foreignPath = "/api/v1/enterprises/" + other + "/resources/" + id;
        mvc.perform(get(foreignPath).cookie(admin.cookie())).andExpect(status().isNotFound());
        write(HttpMethod.POST, foreignPath + "/publish", admin, "2", UUID.randomUUID().toString(), Map.of("releaseNote", "跨企业拒绝")).andExpect(status().isNotFound());
    }

    private JsonNode concurrentPublish(String id, String key, Object body, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发发布测试未开始");
        }
        return data(publish(id, "1", body, key).andExpect(status().isCreated()).andReturn());
    }

    @Test
    void copyRemovesOnlyUnavailableReferencesAndRequiresAnExplicitDraftSaveBeforePublication() throws Exception {
        String first = create("skill", "后来撤销的技能", skill()).at("/resource/id").asText();
        String firstVersion = data(publish(first, "1", Map.of("releaseNote", "技能首版"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String second = create("skill", "继续可用的技能", skill()).at("/resource/id").asText();
        String secondVersion = data(publish(second, "1", Map.of("releaseNote", "技能首版"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        var config = agent();
        config.putArray("skillVersionIds").add(firstVersion).add(secondVersion);
        String original = create("agent", "带固定依赖的员工", config).at("/resource/id").asText();
        publish(original, "1", Map.of("releaseNote", "员工首版", "listing", Map.of("listed", true, "hirePolicy", "approval"), "grants", List.of(Map.of("subjectType", "enterprise", "subjectId", enterprise, "capability", "use"))), UUID.randomUUID().toString()).andExpect(status().isCreated());
        versions.revoke(enterprise, firstVersion, Instant.now());
        String key = UUID.randomUUID().toString();
        var copied = data(write(HttpMethod.POST, resource(original) + "/copy", admin, null, key, Map.of("name", "本人副本")).andExpect(status().isCreated()).andReturn());
        assertEquals(copied, data(write(HttpMethod.POST, resource(original) + "/copy", admin, null, key, Map.of("name", "本人副本")).andExpect(status().isCreated()).andReturn()));
        String id = copied.at("/resource/id").asText();
        assertTrue(copied.at("/resource/publishedVersion").isNull());
        assertTrue(copied.path("grants").isEmpty());
        assertFalse(copied.at("/resource/listing/listed").asBoolean());
        assertEquals(json.valueToTree(List.of(secondVersion)), copied.at("/draft/skillVersionIds"));
        assertTrue(copied.path("fieldErrors").has("config.skillVersionIds"));
        publish(id, "1", Map.of("releaseNote", "尚未确认修正"), UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity());
        write(HttpMethod.PUT, resource(id) + "/draft", admin, "1", UUID.randomUUID().toString(), Map.of("name", "本人副本", "description", "确认仅保留可用依赖", "tagIds", List.of(), "config", copied.path("draft"))).andExpect(status().isOk());
        var published = data(publish(id, "2", Map.of("releaseNote", "副本首版"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn());
        String version = published.at("/version/id").asText();
        var changed = copied.withObject("draft").put("instructions", "这段草稿会被历史内容替换。");
        write(HttpMethod.PUT, resource(id) + "/draft", admin, "3", UUID.randomUUID().toString(), Map.of("name", "暂时改名", "description", "草稿", "tagIds", List.of(), "config", changed)).andExpect(status().isOk());
        var restored = data(write(HttpMethod.POST, resource(id) + "/versions/" + version + "/load-draft", admin, "4", UUID.randomUUID().toString(), null).andExpect(status().isOk()).andReturn());
        assertEquals("先确认输入，再整理可用信息。", restored.at("/draft/instructions").asText());
        assertEquals(version, restored.at("/resource/publishedVersion/id").asText());
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, id))));
        write(HttpMethod.POST, resource(id) + "/versions/" + secondVersion + "/load-draft", admin, "5", UUID.randomUUID().toString(), null).andExpect(status().isNotFound());
    }

    @Test
    void tagDeletionInvalidatesStaleResourceEditsAndModelOptionsContainNoSecrets() throws Exception {
        var tag = data(write(HttpMethod.POST, base() + "/tags", admin, null, UUID.randomUUID().toString(), Map.of("name", "工作资料")).andExpect(status().isCreated()).andReturn());
        String tagId = tag.path("id").asText();
        var created = data(write(HttpMethod.POST, base() + "/resources", admin, null, UUID.randomUUID().toString(), Map.of("kind", "agent", "name", "带标签资源", "description", "实际标签关系", "tagIds", List.of(tagId), "config", agent())).andExpect(status().isCreated()).andReturn());
        String id = created.at("/resource/id").asText();
        assertEquals(tagId, detail(id).at("/resource/tags/0/id").asText());
        write(HttpMethod.PATCH, base() + "/tags/" + tagId, admin, "1", UUID.randomUUID().toString(), Map.of("name", "正式资料")).andExpect(status().isOk());
        assertEquals("正式资料", detail(id).at("/resource/tags/0/name").asText());
        write(HttpMethod.DELETE, base() + "/tags/" + tagId, admin, "2", UUID.randomUUID().toString(), null).andExpect(status().isOk());
        assertTrue(detail(id).at("/resource/tags").isEmpty());
        assertEquals("2", detail(id).at("/resource/revision").asText());
        write(HttpMethod.PUT, resource(id) + "/draft", admin, "1", UUID.randomUUID().toString(), Map.of("name", "旧页面", "description", "旧内容", "tagIds", List.of(tagId), "config", agent())).andExpect(status().isConflict());
        var options = data(mvc.perform(get(base() + "/model-profiles").cookie(admin.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals(1, options.size());
        schemas.validate("ModelProfile", options.get(0));
        assertEquals(model, options.get(0).path("id").asText());
        assertFalse(options.toString().contains("baseUrl"));
        assertFalse(options.toString().contains("credential"));
        assertFalse(options.toString().contains("isolated-model-test-key"));
    }

    private void assertUnpublished(String id) throws Exception {
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceVersionSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceVersionRow>().eq(ResourceVersionRow::getResourceId, id))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceAuthorizationSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceGrantRow>().eq(ResourceGrantRow::getResourceId, id))));
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(AgentListingMapper.class).selectCount(new LambdaQueryWrapper<AgentListingRow>().eq(AgentListingRow::getAgentId, id))));
        assertEquals(1, Math.toIntExact(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getId, id).select(ResourceRow::getNextVersionNo)).stream().map(value -> value.getNextVersionNo()).toList())));
        assertEquals("1", detail(id).at("/resource/revision").asText());
        assertTrue(detail(id).at("/resource/publishedVersion").isNull());
    }

    @Test
    void listsOnlyAuthorizedResourcesBeforePagingAndKeepsNamePositionWhenEarlierRowsChange() throws Exception {
        create("agent", "A0 不可查看", agent());
        String first = create("agent", "A1 可查看", agent()).at("/resource/id").asText();
        String second = create("agent", "B1 可查看", agent()).at("/resource/id").asText();
        String roleId = UUID.randomUUID().toString();
        permissions.insertRole(roleId, enterprise, "list-viewer", "允许查看与复制", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, roleId, Set.of("agent.view", "agent.create"));
        String username = "list-" + UUID.randomUUID();
        var viewer = EnterpriseTestData.member(users, permissions, enterprise, username, PASSWORD, "资源查看者", List.of(roleId));
        var actor = new AuthContext(users.findById(adminId).orElseThrow(), enterprise, Set.of());
        for (String id : List.of(first, second)) {
            grants.replace(actor, id, 1L, List.of(new ResourceGrantSpec("user", viewer.id(), "view")));
        }
        var loggedIn = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", PASSWORD)).andExpect(status().isOk()).andReturn();
        Cookie cookie = loggedIn.getResponse().getCookie("SESSION");
        var page = data(mvc.perform(get(base() + "/resources").cookie(cookie).param("kind", "agent").param("limit", "1").param("sort", "name_asc")).andExpect(status().isOk()).andReturn());
        assertEquals(1, page.path("items").size());
        assertEquals(first, page.at("/items/0/id").asText());
        schemas.validate("ResourceSummary", page.at("/items/0"));
        assertEquals(json.valueToTree(List.of("copy")), page.at("/items/0/allowedActions"));
        assertTrue(page.path("hasMore").asBoolean());
        String cursor = page.path("nextCursor").asText();
        write(HttpMethod.PUT, resource(first) + "/draft", admin, "2", UUID.randomUUID().toString(), Map.of("name", "Z1 已改名", "description", "新的草稿名称", "tagIds", List.of(), "config", agent())).andExpect(status().isOk());
        var next = data(mvc.perform(get(base() + "/resources").cookie(cookie).param("kind", "agent").param("limit", "1").param("sort", "name_asc").param("cursor", cursor)).andExpect(status().isOk()).andReturn());
        assertEquals(second, next.at("/items/0/id").asText());
        mvc.perform(get(base() + "/resources").cookie(cookie).param("kind", "agent").param("sort", "name_asc").param("query", "修改筛选").param("cursor", cursor)).andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/resources").cookie(admin.cookie()).param("kind", "agent").param("sort", "name_asc").param("cursor", cursor)).andExpect(status().isBadRequest());
        grants.replace(actor, second, 2L, List.of());
        var afterRevocation = data(mvc.perform(get(base() + "/resources").cookie(cookie).param("kind", "agent").param("sort", "name_asc")).andExpect(status().isOk()).andReturn());
        assertEquals(1, afterRevocation.path("items").size());
        assertEquals(first, afterRevocation.at("/items/0/id").asText());
    }

    private JsonNode create(String kind, String name, JsonNode config) throws Exception {
        var value = data(write(HttpMethod.POST, base() + "/resources", admin, null, UUID.randomUUID().toString(), Map.of("kind", kind, "name", name, "description", "根据已授权资料完成整理", "tagIds", List.of(), "config", config)).andExpect(status().isCreated()).andReturn());
        schemas.validate("ResourceDetail", value);
        return value;
    }

    @Test
    void anEditorCannotDelegateAndTransferRequiresAnEligibleCurrentMember() throws Exception {
        String id = create("agent", "待转交资源", agent()).at("/resource/id").asText();
        String roleId = resourceRole(Set.of("agent.view", "agent.edit", "resource.grants.manage"));
        String username = "recipient-" + UUID.randomUUID();
        var recipient = EnterpriseTestData.member(users, permissions, enterprise, username, PASSWORD, "接收成员", List.of(roleId));
        var loggedIn = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", PASSWORD)).andExpect(status().isOk()).andReturn();
        var editor = csrf(loggedIn.getResponse().getCookie("SESSION"));
        write(HttpMethod.PUT, resource(id) + "/grants", admin, "1", UUID.randomUUID().toString(), Map.of("grants", List.of(Map.of("subjectType", "user", "subjectId", recipient.id(), "capability", "edit")))).andExpect(status().isOk());
        mvc.perform(get(resource(id)).cookie(editor.cookie())).andExpect(status().isOk());
        mvc.perform(get(resource(id) + "/grants").cookie(editor.cookie())).andExpect(status().isNotFound());
        mvc.perform(get(resource(id) + "/subjects").cookie(editor.cookie()).param("subjectType", "user")).andExpect(status().isNotFound());
        write(HttpMethod.PUT, resource(id) + "/grants", editor, "2", UUID.randomUUID().toString(), Map.of("grants", List.of())).andExpect(status().isNotFound());
        write(HttpMethod.PUT, resource(id) + "/owner", editor, "2", UUID.randomUUID().toString(), Map.of("ownerUserId", recipient.id())).andExpect(status().isNotFound());
        var unqualified = EnterpriseTestData.member(users, permissions, enterprise, "ordinary-" + UUID.randomUUID(), PASSWORD, "没有编辑权限的成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        write(HttpMethod.PUT, resource(id) + "/owner", admin, "2", UUID.randomUUID().toString(), Map.of("ownerUserId", unqualified.id())).andExpect(status().isUnprocessableEntity());
        var recipients = data(mvc.perform(get(resource(id) + "/subjects").cookie(admin.cookie()).param("subjectType", "user").param("purpose", "owner").param("subjectIds", recipient.id() + "," + unqualified.id() + "," + UUID.randomUUID())).andExpect(status().isOk()).andReturn());
        assertEquals(1, recipients.path("items").size());
        assertEquals(recipient.id(), recipients.at("/items/0/id").asText());
        schemas.validate("ResourceSubjectOption", recipients.at("/items/0"));
        assertFalse(recipients.toString().contains("email"));
        assertEquals(adminId, detail(id).at("/resource/owner/id").asText());
        String key = UUID.randomUUID().toString();
        var body = Map.of("ownerUserId", recipient.id().toUpperCase(Locale.ROOT));
        var transferred = data(write(HttpMethod.PUT, resource(id) + "/owner", admin, "2", key, body).andExpect(status().isOk()).andReturn());
        assertEquals(recipient.id(), transferred.at("/owner/id").asText());
        assertEquals(transferred, data(write(HttpMethod.PUT, resource(id) + "/owner", admin, "2", key, body).andExpect(status().isOk()).andReturn()));
        mvc.perform(get(resource(id) + "/grants").cookie(editor.cookie())).andExpect(status().isOk());
        var subjects = data(mvc.perform(get(resource(id) + "/subjects").cookie(editor.cookie()).param("subjectType", "user").param("subjectIds", unqualified.id())).andExpect(status().isOk()).andReturn());
        assertEquals("没有编辑权限的成员", subjects.at("/items/0/name").asText());
        write(HttpMethod.PUT, resource(id) + "/grants", editor, "3", UUID.randomUUID().toString(), Map.of("grants", List.of())).andExpect(status().isOk());
        assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceAuthorizationSqlMapper.class).selectCount(new LambdaQueryWrapper<ResourceGrantRow>().eq(ResourceGrantRow::getResourceId, id))));
        assertEquals(4, Math.toIntExact(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceSqlMapper.class).selectList(new LambdaQueryWrapper<ResourceRow>().eq(ResourceRow::getId, id).select(ResourceRow::getRevision)).stream().map(value -> value.getRevision()).toList())));
    }

    @Test
    void usableVersionOptionsRequireUseGrantsAndNeverReturnConfiguration() throws Exception {
        String id = create("skill", "允许引用的技能", skill()).at("/resource/id").asText();
        String version = data(publish(id, "1", Map.of("releaseNote", "首版"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String hidden = create("skill", "没有授权的较新技能", skill()).at("/resource/id").asText();
        String hiddenVersion = data(publish(hidden, "1", Map.of("releaseNote", "不能被选中"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String username = "options-" + UUID.randomUUID();
        String roleId = resourceRole(Set.of("capabilities.view", "agent.edit", "skill.use"));
        var editor = EnterpriseTestData.member(users, permissions, enterprise, username, PASSWORD, "配置编辑者", List.of(roleId));
        write(HttpMethod.PUT, resource(id) + "/grants", admin, "2", UUID.randomUUID().toString(), Map.of("grants", List.of(Map.of("subjectType", "user", "subjectId", editor.id(), "capability", "use")))).andExpect(status().isOk());
        var loggedIn = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", PASSWORD)).andExpect(status().isOk()).andReturn();
        Cookie cookie = loggedIn.getResponse().getCookie("SESSION");
        mvc.perform(get(resource(id)).cookie(cookie)).andExpect(status().isForbidden());
        var options = data(mvc.perform(get(base() + "/resources/usable-versions").cookie(cookie).param("kind", "skill").param("limit", "1")).andExpect(status().isOk()).andReturn());
        assertEquals(1, options.path("items").size());
        schemas.validate("UsableVersion", options.at("/items/0"));
        assertEquals(version, options.at("/items/0/versionId").asText());
        assertFalse(options.toString().contains("instructions"));
        assertFalse(options.path("hasMore").asBoolean());
        var selected = data(mvc.perform(get(base() + "/resources/usable-versions").cookie(cookie).param("kind", "skill").param("versionIds", version + "," + hiddenVersion)).andExpect(status().isOk()).andReturn());
        assertEquals(1, selected.path("items").size());
        assertEquals(version, selected.at("/items/0/versionId").asText());
        versions.revoke(enterprise, version, Instant.now());
        var revoked = data(mvc.perform(get(base() + "/resources/usable-versions").cookie(cookie).param("kind", "skill")).andExpect(status().isOk()).andReturn());
        assertTrue(revoked.path("items").isEmpty());
        var unavailable = data(mvc.perform(get(base() + "/resources/usable-versions").cookie(cookie).param("kind", "skill").param("versionIds", version)).andExpect(status().isOk()).andReturn());
        assertTrue(unavailable.path("items").isEmpty());
    }

    private String resourceRole(Set<String> codes) {
        String id = UUID.randomUUID().toString();
        permissions.insertRole(id, enterprise, "resource-role-" + id, "资源操作测试角色", "", DataScope.ENTERPRISE, false, Instant.now());
        permissions.replaceRolePermissions(enterprise, id, codes);
        return id;
    }

    @Test
    void deletionAllowsPublishedDependenciesWithoutDisclosingTheirPrivateNames() throws Exception {
        String target = create("skill", "目标技能", skill()).at("/resource/id").asText();
        String targetVersion = data(publish(target, "1", Map.of("releaseNote", "技能首版"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        var config = agent();
        config.putArray("skillVersionIds").add(targetVersion);
        String parent = create("agent", "不可披露的调用方名称", config).at("/resource/id").asText();
        String parentVersion = data(publish(parent, "1", Map.of("releaseNote", "员工首版"), UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        String roleId = resourceRole(Set.of("skill.view", "skill.edit", "skill.delete"));
        String username = "limited-delete-" + UUID.randomUUID();
        var user = EnterpriseTestData.member(users, permissions, enterprise, username, PASSWORD, "受限维护者", List.of(roleId));
        write(HttpMethod.PUT, resource(target) + "/grants", admin, "2", UUID.randomUUID().toString(), Map.of("grants", List.of(Map.of("subjectType", "user", "subjectId", user.id(), "capability", "edit")))).andExpect(status().isOk());
        var loggedIn = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", PASSWORD)).andExpect(status().isOk()).andReturn();
        var actor = csrf(loggedIn.getResponse().getCookie("SESSION"));
        var impact = data(mvc.perform(get(resource(target) + "/impact").cookie(actor.cookie())).andExpect(status().isOk()).andReturn());
        assertTrue(impact.path("visibleDependencies").isEmpty());
        assertEquals(1, impact.path("hiddenDependencyCount").asInt());
        assertFalse(impact.toString().contains("不可披露"));
        assertTrue(impact.path("canDelete").asBoolean());
        write(HttpMethod.DELETE, resource(target), actor, "3", UUID.randomUUID().toString(), null).andExpect(status().isOk());
        write(HttpMethod.POST, resource(parent) + "/versions/" + targetVersion + "/revoke", admin, "2", UUID.randomUUID().toString(), Map.of("reason", "错误资源不能撤销")).andExpect(status().isNotFound());
        var revoked = data(write(HttpMethod.POST, resource(parent) + "/versions/" + parentVersion + "/revoke", admin, "2", UUID.randomUUID().toString(), Map.of("reason", "停止使用这份旧配置")).andExpect(status().isOk()).andReturn());
        assertEquals("revoked", revoked.path("status").asText());
        assertEquals(targetVersion, data(mvc.perform(get(resource(parent) + "/versions/" + parentVersion).cookie(admin.cookie())).andExpect(status().isOk()).andReturn()).at("/config/skillVersionIds/0").asText());
    }

    private JsonNode detail(String id) throws Exception {
        return data(mvc.perform(get(resource(id)).cookie(admin.cookie())).andExpect(status().isOk()).andReturn());
    }

    private ResultActions publish(String id, String revision, Object body, String key) throws Exception {
        return write(HttpMethod.POST, resource(id) + "/publish", admin, revision, key, body);
    }

    private ObjectNode skill() throws Exception {
        return (ObjectNode) json.readTree("""
            {"icon":"BookOpen","color":"purple","scenario":"","inputDescription":"","instructions":"按输入整理信息。",
             "outputDescription":"","example":"","pluginVersionIds":[],"knowledgeVersionIds":[],"showInWorkspace":false}
            """);
    }

    private ObjectNode agent() throws Exception {
        var value = (ObjectNode) json.readTree("""
            {"icon":"Sparkles","color":"purple","agentType":"chat","businessRole":"资料整理","modelProfileId":null,
             "instructions":"先确认输入，再整理可用信息。","temperature":0.7,"maxSteps":20,"timeoutSeconds":120,
             "attachmentsEnabled":false,"welcomeMessage":"","suggestedQuestions":[],"skillVersionIds":[],"pluginVersionIds":[],
             "knowledgeVersionIds":[],"dataVersionIds":[],"workflowVersionIds":[],"entryWorkflowVersionId":null,"historyMessageLimit":20,
             "memoryEnabled":false,"memoryFields":[],"businessTerms":[],"researchSubagentEnabled":false,"publicExamples":[]}
            """);
        return value.put("modelProfileId", model);
    }

    private SessionToken csrf(Cookie cookie) throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (cookie != null) {
            request.cookie(cookie);
        }
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie next = response.getResponse().getCookie("SESSION");
        if (next == null) {
            next = cookie;
        }
        assertNotNull(next);
        return new SessionToken(next, data(response).path("token").asText());
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

    private JsonNode data(MvcResult response) throws Exception {
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }

    private String base() {
        return "/api/v1/enterprises/" + enterprise;
    }

    private String resource(String id) {
        return base() + "/resources/" + id;
    }
}

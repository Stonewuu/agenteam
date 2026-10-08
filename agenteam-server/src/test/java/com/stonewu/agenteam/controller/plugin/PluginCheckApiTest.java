package com.stonewu.agenteam.controller.plugin;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.springframework.context.annotation.Import;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceDraftTableMapper;
import com.stonewu.agenteam.mapper.security.CredentialMapper;
import com.stonewu.agenteam.mapper.security.CredentialSqlMapper;
import com.stonewu.agenteam.mapper.test.audit.AuditEventFixtureMapper;
import com.stonewu.agenteam.mapper.test.http.ApiRequestFixtureMapper;
import com.stonewu.agenteam.mapper.test.resource.ResourceDraftFixtureMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import com.stonewu.agenteam.model.security.entity.CredentialRow;
import com.stonewu.agenteam.service.enterprise.EnterpriseProvisioningService;
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

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实接口、数据库和远程服务共同验证检查、发布及草稿并发修改。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(SharedEnterpriseTestEdition.class)
class PluginCheckApiTest {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    private static final PluginTestServer REMOTE = new PluginTestServer();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AuthMapper users;

    @Autowired
    private EnterpriseProvisioningService enterprises;

    @Autowired
    private MybatisTestDatabase databaseAccess;

    private final ApiContractAssertions schemas = new ApiContractAssertions();

    @Autowired
    private PluginToolMapper tools;

    @Autowired
    private PermissionMapper permissions;

    @Autowired
    private CredentialMapper credentialRecords;

    private Session session;

    private String user;

    private String enterprise;

    private record Session(Cookie cookie, String token) {
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
        registry.add("network.allowed-private-origins", REMOTE::origin);
    }

    @BeforeAll
    void bootstrap() throws Exception {
        var response = write(HttpMethod.POST, "/api/v1/auth/bootstrap", csrf(null), null, UUID.randomUUID().toString(), Map.of("setupCredential", "isolated-invitation-setup-credential", "username", "plugin-check-admin", "displayName", "插件管理员", "password", "插件接口验收使用的独立完整口令2026!", "enterpriseName", "插件基础企业", "email", "plugin-check@example.test", "timezone", "Asia/Shanghai")).andExpect(status().isCreated()).andReturn();
        user = data(response).path("id").asText();
        session = csrf(response.getResponse().getCookie("SESSION"));
    }

    @BeforeEach
    void prepare() {
        REMOTE.reset();
        enterprise = enterprises.create("插件测试企业", users.findById(user).orElseThrow(), Instant.now()).enterpriseId();
    }

    @AfterAll
    void close() throws Exception {
        REMOTE.close();
        ENVIRONMENT.close();
    }

    @Test
    void checkSelectionAndPublicationPreserveCaseAndReuseCompletedRequests() throws Exception {
        var config = plugin();
        String id = create(config);
        String checkKey = UUID.randomUUID().toString();
        var checked = data(write(HttpMethod.POST, checkPath(id), session, "1", checkKey, null).andExpect(status().isOk()).andReturn());
        schemas.validate("ConnectionCheck", checked);
        assertTrue(checked.path("success").asBoolean());
        var listed = data(mvc.perform(get(base() + "/plugins/" + id + "/tools").cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals(2, listed.size());
        assertFalse(listed.get(0).path("enabled").asBoolean());
        listed.forEach(tool -> schemas.validate("PluginTool", tool));
        config.set("enabledToolNames", json.valueToTree(List.of("Read_Item", "read_item")));
        save(id, "1", config).andExpect(status().isOk());
        assertEquals(checked, data(write(HttpMethod.POST, checkPath(id), session, "1", checkKey, null).andExpect(status().isOk()).andReturn()));
        assertEquals(1, REMOTE.methods.stream().filter(value -> value.equals("initialize")).count());
        publish(id, "2").andExpect(status().isConflict());
        check(id, "2").andExpect(status().isOk());
        var detail = data(mvc.perform(get(resource(id)).cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("ResourceDetail", detail);
        assertTrue(detail.at("/connectionCheck/success").asBoolean());
        String version = data(publish(id, "2").andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        assertEquals(2, tools.list(enterprise, version).size());
        assertTrue(tools.list(enterprise, version).stream().allMatch(tool -> tool.enabled() && tool.definition().operationClass().equals("unknown")));
        assertFalse(REMOTE.methods.contains("tools/call"));
    }

    @Test
    void expiredEvidenceAndChangedSelectedToolRequireAnotherReview() throws Exception {
        var config = plugin();
        config.set("enabledToolNames", json.valueToTree(List.of("Read_Item")));
        String id = create(config);
        check(id, "1").andExpect(status().isOk());
        databaseAccess.mapper(ResourceDraftFixtureMapper.class).pluginCheckApiExpiredEvidenceAndChangedSelectedToolRequireAnotherReviewUpdate(Instant.now().minusSeconds(601).toString(), id);
        assertEquals("RESOURCE_CHECK_REQUIRED", error(publish(id, "1").andExpect(status().isConflict()).andReturn()));
        REMOTE.tools = json.valueToTree(List.of(REMOTE.tool("Read_Item", 12), REMOTE.tool("read_item", 1)));
        var refreshed = data(check(id, "1").andExpect(status().isOk()).andReturn());
        assertEquals("changed", refreshed.at("/toolChanges/0/change").asText());
        publish(id, "1").andExpect(status().isUnprocessableEntity());
        check(id, "1").andExpect(status().isOk());
        publish(id, "1").andExpect(status().isUnprocessableEntity());
        REMOTE.status = 403;
        check(id, "1").andExpect(status().isOk());
        REMOTE.status = 200;
        check(id, "1").andExpect(status().isOk());
        publish(id, "1").andExpect(status().isUnprocessableEntity());
        save(id, "1", config).andExpect(status().isOk());
        check(id, "2").andExpect(status().isOk());
        String version = data(publish(id, "2").andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        assertEquals(12, tools.list(enterprise, version).getFirst().definition().inputSchema().at("/properties/text/minLength").asInt());
        REMOTE.tools = json.valueToTree(List.of(REMOTE.tool("read_item", 1)));
        check(id, "3").andExpect(status().isOk());
        publish(id, "3").andExpect(status().isUnprocessableEntity());
        assertEquals(1, tools.list(enterprise, version).size());
        REMOTE.tools = json.valueToTree(List.of());
        check(id, "3").andExpect(status().isOk());
        assertTrue(data(mvc.perform(get(base() + "/plugins/" + id + "/tools").cookie(session.cookie())).andExpect(status().isOk()).andReturn()).isEmpty());
    }

    @Test
    void remoteInspectionDoesNotHoldDatabaseLocksAndCannotOverwriteNewDraft() throws Exception {
        var config = plugin();
        String id = create(config);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        REMOTE.beforeList = () -> {
            reached.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> check(id, "1").andReturn());
            assertTrue(reached.await(3, TimeUnit.SECONDS));
            config.put("timeoutSeconds", 9);
            try {
                save(id, "1", config).andExpect(status().isOk());
            } finally {
                release.countDown();
            }
            var result = pending.get(5, TimeUnit.SECONDS);
            assertEquals(409, result.getResponse().getStatus());
            assertEquals("VERSION_CONFLICT", error(result));
            assertEquals(0, Math.toIntExact(databaseAccess.mapper(ResourceDraftTableMapper.class).selectCount(new LambdaQueryWrapper<ResourceDraftRow>().eq(ResourceDraftRow::getResourceId, (id)).isNotNull(ResourceDraftRow::getValidationJson))));
        }
    }

    @Test
    void failedCheckIsSavedWithoutRemoteBodyAndCrossEnterpriseLookupIsDenied() throws Exception {
        String id = create(plugin());
        REMOTE.status = 403;
        var failed = data(check(id, "1").andExpect(status().isOk()).andReturn());
        assertFalse(failed.path("success").asBoolean());
        assertFalse(failed.toString().contains("原始内容"));
        assertEquals("RESOURCE_CHECK_REQUIRED", error(publish(id, "1").andExpect(status().isConflict()).andReturn()));
        String other = enterprises.create("其他插件企业", users.findById(user).orElseThrow(), Instant.now()).enterpriseId();
        write(HttpMethod.POST, "/api/v1/enterprises/" + other + "/plugins/" + id + "/check", session, "1", UUID.randomUUID().toString(), null).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/enterprises/" + other + "/plugins/" + id + "/tools").cookie(session.cookie())).andExpect(status().isNotFound());
    }

    @Test
    void earlierSlowInspectionCannotReplaceARecentlySavedResult() throws Exception {
        String id = create(plugin());
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sequence = new AtomicInteger();
        REMOTE.beforeList = () -> {
            if (sequence.incrementAndGet() != 1) {
                return;
            }
            reached.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var earlier = executor.submit(() -> check(id, "1").andReturn());
            assertTrue(reached.await(3, TimeUnit.SECONDS));
            try {
                check(id, "1").andExpect(status().isOk());
            } finally {
                release.countDown();
            }
            var result = earlier.get(5, TimeUnit.SECONDS);
            assertEquals(409, result.getResponse().getStatus());
            assertEquals("RESOURCE_CHECK_SUPERSEDED", error(result));
        }
    }

    @Test
    void builtinRegistryRejectsArbitraryNamesAndPublishesRegisteredToolsWithoutRemoteCheck() throws Exception {
        var config = plugin().put("pluginType", "builtin").put("builtinCode", "web_read").putNull("transport").putNull("endpoint");
        config.set("enabledToolNames", json.valueToTree(List.of("read_url")));
        String id = create(config);
        String version = data(publish(id, "1").andExpect(status().isCreated()).andReturn()).at("/version/id").asText();
        assertEquals("read", tools.list(enterprise, version).getFirst().definition().operationClass());
        var registry = data(mvc.perform(get(base() + "/plugins/builtins").cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        assertTrue(registry.findValuesAsText("code").contains("web_read"));
        for (var builtin : registry) {
            schemas.validate("BuiltinPlugin", builtin);
        }
        config.put("builtinCode", "arbitrary_method");
        write(HttpMethod.POST, base() + "/resources", session, null, UUID.randomUUID().toString(), body(config)).andExpect(status().isUnprocessableEntity());
        var publicHttp = write(HttpMethod.POST, base() + "/resources", session, null, UUID.randomUUID().toString(), body(plugin().put("endpoint", "http://example.com/mcp"))).andExpect(status().isUnprocessableEntity()).andReturn();
        assertEquals("NETWORK_ADDRESS_DENIED", error(publicHttp));
        assertTrue(REMOTE.methods.isEmpty());
    }

    @Test
    void credentialsAreEncryptedRotatedAtNextConnectionAndProtectedByPublishedReferences() throws Exception {
        String firstSecret = "isolated-secret-before-rotation", nextSecret = "isolated-secret-after-rotation";
        String key = UUID.randomUUID().toString();
        var body = Map.of("name", "远程工具凭据", "kind", "bearer", "secret", firstSecret);
        var created = data(write(HttpMethod.POST, base() + "/credentials", session, null, key, body).andExpect(status().isCreated()).andReturn());
        String credential = created.path("id").asText();
        schemas.validate("CredentialSummary", created);
        assertFalse(created.toString().contains(firstSecret));
        assertEquals(created, data(write(HttpMethod.POST, base() + "/credentials", session, null, key, body).andExpect(status().isCreated()).andReturn()));
        assertEquals(1, Math.toIntExact(databaseAccess.mapper(CredentialSqlMapper.class).selectCount(new LambdaQueryWrapper<CredentialRow>().eq(CredentialRow::getEnterpriseId, (enterprise)))));
        assertFalse(credentialRecords.find(enterprise, credential).orElseThrow().toString().contains(firstSecret));
        var config = plugin().put("credentialId", credential.toUpperCase(Locale.ROOT));
        String id = create(config);
        check(id, "1").andExpect(status().isOk());
        assertTrue(REMOTE.authorizationHeaders.stream().allMatch(value -> value.equals("Bearer " + firstSecret)));
        publish(id, "1").andExpect(status().isCreated());
        String rotateKey = UUID.randomUUID().toString();
        var rotated = data(write(HttpMethod.POST, base() + "/credentials/" + credential + "/rotate", session, "1", rotateKey, Map.of("secret", nextSecret)).andExpect(status().isOk()).andReturn());
        assertEquals("2", rotated.path("revision").asText());
        assertEquals(1, rotated.path("referenceCount").asInt());
        assertEquals(rotated, data(write(HttpMethod.POST, base() + "/credentials/" + credential + "/rotate", session, "1", rotateKey, Map.of("secret", nextSecret)).andExpect(status().isOk()).andReturn()));
        REMOTE.authorizationHeaders.clear();
        check(id, "2").andExpect(status().isOk());
        assertTrue(REMOTE.authorizationHeaders.stream().allMatch(value -> value.equals("Bearer " + nextSecret)));
        assertFalse(REMOTE.authorizationHeaders.isEmpty());
        REMOTE.tools = json.valueToTree(List.of(Map.of("name", "unsafe_description", "description", "认证信息 " + nextSecret, "inputSchema", Map.of("type", "object"))));
        var unsafe = data(check(id, "2").andExpect(status().isOk()).andReturn());
        assertFalse(unsafe.path("success").asBoolean());
        assertFalse(unsafe.toString().contains(nextSecret));
        assertFalse(DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ResourceDraftTableMapper.class).selectList(new LambdaQueryWrapper<ResourceDraftRow>().select(ResourceDraftRow::getValidationJson).eq(ResourceDraftRow::getResourceId, (id))).stream().map(fixtureRecord -> fixtureRecord.getValidationJson()).toList()).contains(nextSecret));
        REMOTE.tools = json.valueToTree(List.of(REMOTE.tool("Read_Item", 1), REMOTE.tool("read_item", 1)));
        write(HttpMethod.POST, base() + "/credentials/" + credential + "/rotate", session, "2", UUID.randomUUID().toString(), Map.of("secret", "")).andExpect(status().isUnprocessableEntity());
        config.putNull("credentialId");
        save(id, "2", config).andExpect(status().isOk());
        assertEquals("CREDENTIAL_IN_USE", error(write(HttpMethod.DELETE, base() + "/credentials/" + credential, session, "2", UUID.randomUUID().toString(), null).andExpect(status().isConflict()).andReturn()));
        write(HttpMethod.PATCH, resource(id) + "/status", session, "3", UUID.randomUUID().toString(), Map.of("status", "disabled")).andExpect(status().isOk());
        write(HttpMethod.DELETE, base() + "/credentials/" + credential, session, "2", UUID.randomUUID().toString(), null).andExpect(status().isOk());
        assertEquals("revoked", credentialRecords.find(enterprise, credential).orElseThrow().status());
        var summaries = data(mvc.perform(get(base() + "/credentials").cookie(session.cookie())).andExpect(status().isOk()).andReturn());
        assertFalse(summaries.toString().contains(firstSecret));
        assertFalse(summaries.toString().contains(nextSecret));
        assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(ApiRequestFixtureMapper.class).pluginCheckApiCredentialsAreEncryptedRotatedAtNextConnectionAndProtectedByPublishedReferencesObject(enterprise, firstSecret, nextSecret)));
        assertEquals(0, DataAccessUtils.nullableSingleResult(databaseAccess.mapper(AuditEventFixtureMapper.class).pluginCheckApiCredentialsAreEncryptedRotatedAtNextConnectionAndProtectedByPublishedReferencesObject(enterprise, firstSecret, nextSecret)));
    }

    @Test
    void enterprisePermissionsProtectCredentials() throws Exception {
        var created = data(write(HttpMethod.POST, base() + "/credentials", session, null, UUID.randomUUID().toString(), Map.of("name", "权限检查凭据", "kind", "bearer", "secret", "isolated-plugin-key")).andExpect(status().isCreated()).andReturn());
        String credential = created.path("id").asText();
        String other = enterprises.create("另一个凭据企业", users.findById(user).orElseThrow(), Instant.now()).enterpriseId();
        write(HttpMethod.POST, "/api/v1/enterprises/" + other + "/credentials/" + credential + "/rotate", session, "1", UUID.randomUUID().toString(), Map.of("secret", "another-isolated-key")).andExpect(status().isNotFound());
        String username = "credential-member-" + UUID.randomUUID();
        EnterpriseTestData.member(users, permissions, enterprise, username, "没有凭据权限的独立测试口令2026!", "普通成员", List.of(permissions.builtinRoleId(enterprise, "member")));
        var login = write(HttpMethod.POST, "/api/v1/auth/login", csrf(null), null, UUID.randomUUID().toString(), Map.of("identifier", username, "password", "没有凭据权限的独立测试口令2026!")).andExpect(status().isOk()).andReturn();
        var member = csrf(login.getResponse().getCookie("SESSION"));
        mvc.perform(get(base() + "/credentials").cookie(member.cookie())).andExpect(status().isForbidden());
        write(HttpMethod.POST, base() + "/credentials", member, null, UUID.randomUUID().toString(), Map.of("name", "无权创建", "kind", "bearer", "secret", "another-isolated-key")).andExpect(status().isForbidden());
    }

    private ObjectNode plugin() throws Exception {
        return ((ObjectNode) json.readTree("""
            {"icon":"Box","color":"purple","pluginType":"mcp","builtinCode":null,"transport":"streamable_http",
             "endpoint":"https://example.test/mcp","credentialId":null,"timeoutSeconds":10,"enabledToolNames":[]}
            """)).put("endpoint", REMOTE.endpoint());
    }

    private Map<String, Object> body(JsonNode config) {
        return Map.of("kind", "plugin", "name", "测试插件", "description", "提供测试工具", "tagIds", List.of(), "config", config);
    }

    private String create(JsonNode config) throws Exception {
        return data(write(HttpMethod.POST, base() + "/resources", session, null, UUID.randomUUID().toString(), body(config)).andExpect(status().isCreated()).andReturn()).at("/resource/id").asText();
    }

    private ResultActions check(String id, String revision) throws Exception {
        return write(HttpMethod.POST, checkPath(id), session, revision, UUID.randomUUID().toString(), null);
    }

    private ResultActions publish(String id, String revision) throws Exception {
        return write(HttpMethod.POST, resource(id) + "/publish", session, revision, UUID.randomUUID().toString(), Map.of("releaseNote", "发布验证工具"));
    }

    private ResultActions save(String id, String revision, JsonNode config) throws Exception {
        return write(HttpMethod.PUT, resource(id) + "/draft", session, revision, UUID.randomUUID().toString(), Map.of("name", "测试插件", "description", "提供测试工具", "tagIds", List.of(), "config", config));
    }

    private String base() {
        return "/api/v1/enterprises/" + enterprise;
    }

    private String resource(String id) {
        return base() + "/resources/" + id;
    }

    private String checkPath(String id) {
        return base() + "/plugins/" + id + "/check";
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String error(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText();
    }

    private Session csrf(Cookie cookie) throws Exception {
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
        return new Session(next, data(response).path("token").asText());
    }

    private ResultActions write(HttpMethod method, String path, Session session, String revision, String key, Object body) throws Exception {
        var request = request(method, path).cookie(session.cookie()).header("Origin", "http://localhost:3000").header("X-CSRF-Token", session.token()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
        if (revision != null) {
            request.header("If-Match", "\"" + revision + "\"");
        }
        if (body != null) {
            request.content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andDo(result -> {
            if (result.getResponse().getStatus() == 500 && result.getResolvedException() != null) {
                throw result.getResolvedException();
            }
        });
    }
}

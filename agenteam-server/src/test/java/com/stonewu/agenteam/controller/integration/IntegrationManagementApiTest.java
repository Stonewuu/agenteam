package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationSecretMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationSecretRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.IntegrationCheckService;
import com.stonewu.agenteam.service.integration.IntegrationManagementService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.service.integration.IntegrationTokenService;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MockChannelServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经正式会话和数据库验证管理权限、密钥保护、版本检查与模拟平台校验。
 */
@Import({SharedEnterpriseTestEdition.class, IntegrationManagementApiTest.PlatformConfiguration.class})
class IntegrationManagementApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();

    @TestConfiguration
    static class PlatformConfiguration {
        @Bean
        @Primary
        IntegrationEndpoints testIntegrationEndpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @Autowired
    private IntegrationSecretService secrets;
    @Autowired
    private IntegrationCheckService checks;
    @Autowired
    private IntegrationManagementService management;
    @Autowired
    private IntegrationTokenService tokens;
    @Autowired
    private IntegrationQueryService queries;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void resetPlatform() {
        PLATFORM.reset();
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        PLATFORM.close();
        ENVIRONMENT.close();
    }

    @Test
    void creationEncryptsSecretAndRepeatedRequestDoesNotCreateSecondConnection() throws Exception {
        String key = UUID.randomUUID().toString();
        var input = payload();
        var created = data(write(path(), input, key).andExpect(status().isCreated()).andReturn());
        schemas.validate("Integration", created);
        var providers = data(mvc.perform(get(base() + "/integration-providers").cookie(cookie)).andExpect(status().isOk()).andReturn());
        providers.forEach(provider -> schemas.validate("IntegrationProvider", provider));
        assertEquals(created, data(write(path(), input, key).andExpect(status().isCreated()).andReturn()));
        assertEquals("draft", created.path("status").asText());
        assertTrue(created.path("secretConfigured").asBoolean());
        assertFalse(created.toString().contains("test-private-app-secret"));
        assertFalse(created.has("encryptedValue"));
        String id = created.path("id").asText();
        assertEquals("test-private-app-secret", secrets.load(enterprise, id));
        var secret = databaseAccess.mapper(EnterpriseIntegrationSecretMapper.class).selectOne(
            new LambdaQueryWrapper<EnterpriseIntegrationSecretRow>().eq(EnterpriseIntegrationSecretRow::getConnectionId, id));
        assertFalse(secret.getEncryptedValue().contains("test-private-app-secret"));
        assertEquals(1L, databaseAccess.mapper(EnterpriseIntegrationMapper.class).selectCount(
            new LambdaQueryWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)));
        var list = data(mvc.perform(get(path()).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertEquals(id, list.at("/items/0/id").asText());
        assertFalse(list.toString().contains("test-private-app-secret"));
        change(HttpMethod.PATCH, path() + "/" + id + "/status", Map.of("status", "enabled"), "1", key())
            .andExpect(status().isConflict());
    }

    @Test
    void actualCheckEnablesConfigurationAndRotationRequiresANewCheck() throws Exception {
        var created = create();
        String id = created.path("id").asText();
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.tenant");
        String requestKey = key();
        var checked = data(change(HttpMethod.POST, path() + "/" + id + "/check", null, "1", requestKey)
            .andExpect(status().isOk()).andReturn());
        assertEquals("passed", checked.path("lastCheckStatus").asText());
        schemas.validate("Integration", checked);
        assertEquals("test-feishu-tenant", checked.path("externalTenantId").asText());
        assertEquals("2", checked.path("revision").asText());
        PLATFORM.take();
        PLATFORM.take();
        assertEquals(checked, data(change(HttpMethod.POST, path() + "/" + id + "/check", null, "1", requestKey)
            .andExpect(status().isOk()).andReturn()));
        assertEquals(0, PLATFORM.remainingRequests());
        var enabled = data(change(HttpMethod.PATCH, path() + "/" + id + "/status", Map.of("status", "enabled"), "2", key())
            .andExpect(status().isOk()).andReturn());
        assertEquals("enabled", enabled.path("status").asText());
        var rotated = data(change(HttpMethod.POST, path() + "/" + id + "/rotate-secret", Map.of("secret", "new-test-secret"), "3", key())
            .andExpect(status().isOk()).andReturn());
        assertEquals("disabled", rotated.path("status").asText());
        assertEquals("not_checked", rotated.path("lastCheckStatus").asText());
        assertEquals("new-test-secret", secrets.load(enterprise, id));
        change(HttpMethod.PATCH, path() + "/" + id + "/status", Map.of("status", "enabled"), "4", key())
            .andExpect(status().isConflict());
        change(HttpMethod.PATCH, path() + "/" + id, Map.of("name", "过时修改"), "1", key()).andExpect(status().isConflict());
    }

    @Test
    void lateCheckCannotApproveReplacedCredentialsAndFailureIsRecorded() throws Exception {
        var created = create();
        String id = created.path("id").asText();
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.tenant");
        var prepared = checks.prepare(users.findById(admin).orElseThrow(), enterprise, false, id, 1);
        PLATFORM.take();
        PLATFORM.take();
        change(HttpMethod.POST, path() + "/" + id + "/rotate-secret", Map.of("secret", "changed-before-check"), "1", key())
            .andExpect(status().isOk());
        assertThrows(ApiException.class, () -> management.recordCheck(users.findById(admin).orElseThrow(), false, prepared));
        PLATFORM.reply(401, "feishu.expiredToken", Map.of());
        var failed = data(change(HttpMethod.POST, path() + "/" + id + "/check", null, "2", key()).andExpect(status().isOk()).andReturn());
        PLATFORM.take();
        assertEquals("failed", failed.path("lastCheckStatus").asText());
        assertFalse(failed.path("lastCheckError").asText().isBlank());
        assertFalse(failed.toString().contains("changed-before-check"));
    }

    @Test
    void globalAdministratorCanManageAnotherCompanyWithoutBecomingAMember() throws Exception {
        var other = EnterpriseTestData.member(users, permissions, enterprise, "channel_owner_" + key().substring(0, 8),
            "channel-owner-test-password", "另一企业管理员", List.of(permissions.builtinRoleId(enterprise, "member")));
        String target = provisioning.create("全局接入测试企业", "", null, "Asia/Shanghai", other.id(),
            users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        assertFalse(users.isActiveMember(admin, target));
        String systemPath = "/api/v1/system/enterprises/" + target + "/integrations";
        var configured = data(write(systemPath, payload(), key()).andExpect(status().isCreated()).andReturn());
        assertEquals(target, configured.path("enterpriseId").asText());
        assertFalse(users.isActiveMember(admin, target));
        write("/api/v1/enterprises/" + target + "/integrations", payload(), key()).andExpect(status().isForbidden());
        mvc.perform(get(path() + "/" + configured.path("id").asText()).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test
    void ordinaryMemberCannotManageEitherEnterpriseOrSystemEntry() throws Exception {
        String username = "channel_member_" + key().substring(0, 8), password = "channel-member-test-password";
        EnterpriseTestData.member(users, permissions, enterprise, username, password, "普通成员",
            List.of(permissions.builtinRoleId(enterprise, "member")));
        var anonymous = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie browser = anonymous.getResponse().getCookie("SESSION");
        String initialCsrf = data(anonymous).path("token").asText();
        var login = mvc.perform(post("/api/v1/auth/login").cookie(browser).header("X-CSRF-Token", initialCsrf)
            .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("identifier", username, "password", password))))
            .andExpect(status().isOk()).andReturn();
        browser = login.getResponse().getCookie("SESSION");
        var currentCsrf = data(mvc.perform(get("/api/v1/auth/csrf").cookie(browser)).andExpect(status().isOk()).andReturn())
            .path("token").asText();
        for (String prefix : List.of("/api/v1/enterprises/", "/api/v1/system/enterprises/")) {
            mvc.perform(post(prefix + enterprise + "/integrations").cookie(browser).header("X-CSRF-Token", currentCsrf)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload())))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void applicationTokenIsEncryptedAndCredentialRotationUsesANewCache() throws Exception {
        String id = create().path("id").asText();
        var app = queries.application(queries.require(enterprise, id));
        PLATFORM.reply("feishu.token");
        var first = tokens.get(app);
        PLATFORM.take();
        assertEquals(first.value(), tokens.get(app).value());
        assertEquals(0, PLATFORM.remainingRequests());
        var keys = redis.keys("agenteam:integration:token:" + enterprise + ":" + id + ":*");
        assertEquals(1, keys.size());
        String stored = redis.opsForValue().get(keys.iterator().next());
        assertNotNull(stored);
        assertFalse(stored.contains(first.value()));
        assertTrue(redis.getExpire(keys.iterator().next()) > 1700);
        change(HttpMethod.POST, path() + "/" + id + "/rotate-secret", Map.of("secret", "next-test-secret"), "1", key())
            .andExpect(status().isOk());
        var changed = queries.application(queries.require(enterprise, id));
        assertNotEquals(app.credentialRevision(), changed.credentialRevision());
        PLATFORM.reply("feishu.token");
        tokens.get(changed);
        var request = json.readTree(PLATFORM.take().body());
        assertEquals("next-test-secret", request.path("app_secret").asText());
    }

    @Test
    void lateTokenInvalidationCannotDeleteAFresherToken() throws Exception {
        String id = create().path("id").asText();
        var app = queries.application(queries.require(enterprise, id));
        PLATFORM.reply("feishu.token");
        var old = tokens.get(app);
        PLATFORM.take();
        tokens.invalidate(app, old);
        PLATFORM.raw(200, "{\"code\":0,\"tenant_access_token\":\"test-refreshed-token\",\"expire\":1800}", Map.of());
        var refreshed = tokens.get(app);
        PLATFORM.take();
        tokens.invalidate(app, old);
        assertEquals(refreshed.value(), tokens.get(app).value());
        assertEquals(0, PLATFORM.remainingRequests());
    }

    private JsonNode create() throws Exception {
        return data(write(path(), payload(), key()).andExpect(status().isCreated()).andReturn());
    }

    private Map<String, Object> payload() {
        return Map.of("providerCode", "feishu", "name", "飞书接入", "externalAppId", "cli_test_" + key().replace("-", ""),
            "secret", "test-private-app-secret");
    }

    private String path() {
        return base() + "/integrations";
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动模拟平台", failure);
        }
    }
}

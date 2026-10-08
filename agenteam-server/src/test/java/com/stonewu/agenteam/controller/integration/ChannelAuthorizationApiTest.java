package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经正式会话、模拟平台网络和真实数据库验证绑定与外部登录。 */
@Import({SharedEnterpriseTestEdition.class, ChannelAuthorizationApiTest.PlatformConfiguration.class})
class ChannelAuthorizationApiTest extends ExecutionApiTestSupport {
    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    private static final MockChannelServer PLATFORM = platform();
    private static final AtomicInteger SOURCES = new AtomicInteger();
    @Autowired
    private ChannelAuthorizationSecrets authorizationSecrets;
    private String connection;
    private String loginKey;
    private Browser member;
    private final Map<String, String> reviews = new ConcurrentHashMap<>();

    @TestConfiguration
    static class PlatformConfiguration {
        @Bean
        @Primary
        IntegrationEndpoints channelTestEndpoints() {
            return new IntegrationEndpoints(PLATFORM.origin(), PLATFORM.origin(), PLATFORM.origin());
        }
    }

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    void closeDependencies() throws Exception {
        PLATFORM.close();
        ENVIRONMENT.close();
    }

    @BeforeEach
    void configureConnection() throws Exception {
        PLATFORM.reset();
        reviews.clear();
        var result = data(write(base() + "/integrations", Map.of("providerCode", "feishu", "name", "授权测试飞书",
            "externalAppId", "cli_" + key().replace("-", ""), "secret", "test-auth-secret",
            "bindingEnabled", true, "loginEnabled", true, "messagingEnabled", true), key()).andExpect(status().isCreated()).andReturn());
        connection = result.path("id").asText();
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.tenant");
        change(HttpMethod.POST, base() + "/integrations/" + connection + "/check", null, "1", key()).andExpect(status().isOk());
        PLATFORM.take();
        PLATFORM.take();
        change(HttpMethod.PATCH, base() + "/integrations/" + connection + "/status", Map.of("status", "enabled"), "2", key()).andExpect(status().isOk());
        loginKey = databaseAccess.mapper(EnterpriseIntegrationMapper.class).selectById(connection).getPublicLoginKey();
        member = member();
    }

    @Test
    void authorizationUsesPkceAndRequiresOneTimeConfirmationBeforeBinding() throws Exception {
        var started = begin(member);
        schemas.validate("ChannelAuthorizationUrl", started);
        var query = UriComponentsBuilder.fromUriString(started.path("authorizationUrl").asText()).build().getQueryParams();
        assertEquals("S256", query.getFirst("code_challenge_method"));
        String state = query.getFirst("state");
        var pending = flow(state);
        assertNotEquals(state, pending.getStateHash());
        assertFalse(pending.getTransientEncryptedJson().contains(state));
        callback(member, state);
        assertEquals(0, countBindings());
        var review = data(mvc.perform(get("/api/v1/auth/channel-authorization").cookie(member.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("ChannelAuthorizationReview", review);
        assertEquals("ou_test_member", review.path("externalSubjectId").asText());
        assertEquals(enterprise, review.path("enterpriseId").asText());
        String requestKey = key();
        var bound = data(confirm(member, requestKey).andExpect(status().isCreated()).andReturn());
        schemas.validate("ChannelBinding", bound);
        assertEquals(bound, data(confirm(member, requestKey).andExpect(status().isCreated()).andReturn()));
        assertEquals(1, countBindings());
        var consumed = flow(state);
        assertEquals("consumed", consumed.getStatus());
        assertNull(consumed.getTransientEncryptedJson());
        assertNull(consumed.getConfirmationTokenHash());
        assertFalse(bound.toString().contains("test-auth-secret"));
        confirm(member, key()).andExpect(status().isConflict());
    }

    @Test
    void wrongBrowserWrongConnectionAndReplayCannotUseAuthorizationCode() throws Exception {
        String state = state(begin(member));
        var other = anonymous();
        rawCallback(other, connection, state).andExpect(status().isSeeOther());
        assertEquals("pending", flow(state).getStatus());
        rawCallback(member, key(), state).andExpect(status().isSeeOther());
        assertEquals(0, PLATFORM.remainingRequests());
        callback(member, state);
        confirm(member, key()).andExpect(status().isCreated());
        rawCallback(member, connection, state).andExpect(status().isSeeOther());
        assertEquals(1, countBindings());
        assertEquals(0, PLATFORM.remainingRequests());
    }

    @Test
    void expiredStateAndChangedConfigurationAreRejectedBeforeNetworkCalls() throws Exception {
        String state = state(begin(member));
        var row = flow(state);
        databaseAccess.mapper(ChannelOauthSessionMapper.class).update(new LambdaUpdateWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getId, row.getId()).set(ChannelOauthSessionRow::getCreatedAt, Instant.now().minusSeconds(600))
            .set(ChannelOauthSessionRow::getExpiresAt, Instant.now().minusSeconds(1)));
        rawCallback(member, connection, state).andExpect(status().isSeeOther());
        String next = state(begin(member));
        change(HttpMethod.PATCH, base() + "/integrations/" + connection, Map.of("name", "修改了配置"), "3", key()).andExpect(status().isOk());
        rawCallback(member, connection, next).andExpect(status().isSeeOther());
        assertEquals(0, PLATFORM.remainingRequests());
        assertEquals(0, countBindings());
    }

    @Test
    void switchedAccountCannotCompletePreviousUsersBinding() throws Exception {
        String state = state(begin(member));
        var loggedIn = login(member, "execution-submission-admin", "执行提交测试所使用的独立完整口令");
        rawCallback(loggedIn, connection, state).andExpect(status().isSeeOther());
        assertEquals(0, PLATFORM.remainingRequests());
        assertEquals(0, countBindings());
    }

    @Test
    void wrongExternalTenantAndDeclinedAuthorizationDoNotCreateBinding() throws Exception {
        String state = state(begin(member));
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.userToken");
        PLATFORM.raw(200, "{\"code\":0,\"data\":{\"open_id\":\"ou_wrong\",\"tenant_key\":\"different-tenant\"}}", Map.of());
        rawCallback(member, connection, state).andExpect(status().isSeeOther());
        takeIdentityRequests();
        assertEquals("failed", flow(state).getStatus());
        assertNull(flow(state).getTransientEncryptedJson());
        assertEquals(0, countBindings());
        String next = state(begin(member));
        mvc.perform(get("/api/v1/auth/channel-callbacks/" + connection).cookie(member.cookie()).param("state", next))
            .andExpect(status().isSeeOther());
        assertEquals("failed", flow(next).getStatus());
        assertEquals(0, PLATFORM.remainingRequests());
    }

    @Test
    void twoUsersCannotConcurrentlyClaimTheSameExternalIdentity() throws Exception {
        var second = member();
        callback(member, state(begin(member)));
        callback(second, state(begin(second)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> confirm(member, key()).andReturn().getResponse().getStatus());
            var other = executor.submit(() -> confirm(second, key()).andReturn().getResponse().getStatus());
            assertEquals(List.of(201, 409), List.of(first.get(), other.get()).stream().sorted().toList());
        }
        assertEquals(1, countBindings());
    }

    @Test
    void expiredConfirmationCannotCreateBinding() throws Exception {
        String state = state(begin(member));
        callback(member, state);
        databaseAccess.mapper(ChannelOauthSessionMapper.class).update(new LambdaUpdateWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getId, flow(state).getId())
            .set(ChannelOauthSessionRow::getConfirmationExpiresAt, Instant.now().minusSeconds(1)));
        confirm(member, key()).andExpect(status().isConflict());
        assertEquals(0, countBindings());
    }

    @Test
    void anotherTabsCallbackCannotReplaceTheIdentityShownOnConfirmationPage() throws Exception {
        callback(member, state(begin(member)));
        String shown = reviews.get(member.cookie().getValue());
        callback(member, state(begin(member)));
        writeAs(member, HttpMethod.POST, channels() + "/confirm", Map.of("authorizationId", shown,
            "receiveEnabled", true, "externalLoginEnabled", false), null, key()).andExpect(status().isConflict());
        writeAs(member, HttpMethod.POST, "/api/v1/auth/channel-authorization/cancel", Map.of("authorizationId", shown),
            null, key()).andExpect(status().isConflict());
        assertEquals(0, countBindings());
        confirm(member, key()).andExpect(status().isCreated());
    }

    @Test
    void externalLoginRestrictsCompanyAndGlobalActionsThenUnbindRevokesIt() throws Exception {
        var bound = bind(member);
        String another = provisioning.create("该成员的其他企业", "", null, "Asia/Shanghai", member.userId(),
            users.findById(admin).orElseThrow(), Instant.now()).enterpriseId();
        var external = externalLogin();
        var me = data(mvc.perform(get("/api/v1/auth/me").cookie(external.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals("channel", me.path("authenticationMethod").asText());
        assertEquals(enterprise, me.path("restrictedEnterpriseId").asText());
        assertEquals(1, me.path("enterprises").size());
        assertFalse(me.path("superAdmin").asBoolean());
        mvc.perform(get(base() + "/context").cookie(external.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/enterprises/" + another + "/context").cookie(external.cookie())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/system/enterprises/" + enterprise + "/integrations").cookie(external.cookie())).andExpect(status().isForbidden());
        writeAs(external, HttpMethod.PATCH, "/api/v1/me/profile", Map.of("displayName", "不能修改"), "1", key()).andExpect(status().isForbidden());
        writeAs(member, HttpMethod.DELETE, channels() + "/" + bound.path("id").asText(), null, "1", key()).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(external.cookie())).andExpect(status().isUnauthorized());
        var rebound = bind(member);
        assertNotEquals(bound.path("id").asText(), rebound.path("id").asText());
        assertEquals(2, countBindings());
    }

    @Test
    void disablingThenReenablingLoginDoesNotReviveExistingExternalSession() throws Exception {
        bind(member);
        var external = externalLogin();
        change(HttpMethod.PATCH, base() + "/integrations/" + connection, Map.of("loginEnabled", false), "3", key()).andExpect(status().isOk());
        change(HttpMethod.PATCH, base() + "/integrations/" + connection, Map.of("loginEnabled", true), "4", key()).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(external.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void localReauthenticationRotatesSessionAndRestoresGlobalAccess() throws Exception {
        bind(member);
        var external = externalLogin();
        writeAs(external, HttpMethod.POST, "/api/v1/auth/reauthenticate", Map.of("password", "错误密码"), null, key())
            .andExpect(status().isUnprocessableEntity());
        var response = writeAs(external, HttpMethod.POST, "/api/v1/auth/reauthenticate", Map.of("password", "channel-member-password"), null, key())
            .andExpect(status().isOk()).andReturn();
        assertNotEquals(external.cookie().getValue(), response.getResponse().getCookie("SESSION").getValue());
        assertEquals("password", data(response).path("authenticationMethod").asText());
    }

    @Test
    void superAdministratorCanReceiveButCannotEnableExternalLogin() throws Exception {
        var administrator = new Browser(cookie, csrf, admin, "203.0.113.250");
        callback(administrator, state(begin(administrator)));
        confirm(administrator, key()).andExpect(status().isUnprocessableEntity());
        var bound = data(writeAs(administrator, HttpMethod.POST, channels() + "/confirm",
            Map.of("authorizationId", reviews.get(administrator.cookie().getValue()), "receiveEnabled", true, "externalLoginEnabled", false),
            null, key()).andExpect(status().isCreated()).andReturn());
        assertTrue(bound.path("receiveEnabled").asBoolean());
        assertFalse(bound.path("externalLoginEnabled").asBoolean());
    }

    @Test
    void wecomApplicationAuthorizationAlsoSupportsBindingAndRestrictedLogin() throws Exception {
        var created = data(write(base() + "/integrations", Map.of("providerCode", "wecom", "name", "授权测试企微",
            "externalAppId", "1000001", "externalTenantId", "ww_" + key().replace("-", ""),
            "secret", "test-wecom-auth-secret", "bindingEnabled", true, "loginEnabled", true, "messagingEnabled", true), key())
            .andExpect(status().isCreated()).andReturn());
        connection = created.path("id").asText();
        PLATFORM.reply("wecom.token");
        PLATFORM.reply("wecom.application");
        change(HttpMethod.POST, base() + "/integrations/" + connection + "/check", null, "1", key()).andExpect(status().isOk());
        PLATFORM.take();
        PLATFORM.take();
        change(HttpMethod.PATCH, base() + "/integrations/" + connection + "/status", Map.of("status", "enabled"), "2", key()).andExpect(status().isOk());
        loginKey = databaseAccess.mapper(EnterpriseIntegrationMapper.class).selectById(connection).getPublicLoginKey();
        String state = state(begin(member));
        PLATFORM.reply("wecom.token");
        PLATFORM.reply("wecom.identity");
        rawCallback(member, connection, state).andExpect(status().isSeeOther());
        PLATFORM.take();
        PLATFORM.take();
        reviews.put(member.cookie().getValue(), flow(state).getId());
        confirm(member, key()).andExpect(status().isCreated());
        var browser = anonymous();
        var start = data(writeAs(browser, HttpMethod.POST, "/api/v1/auth/channel-login/" + loginKey,
            Map.of("embeddedClient", true), null, key()).andExpect(status().isOk()).andReturn());
        assertTrue(start.path("authorizationUrl").asText().contains("snsapi_base"));
        PLATFORM.reply("wecom.token");
        PLATFORM.reply("wecom.identity");
        var response = rawCallback(browser, connection, state(start)).andExpect(status().isSeeOther()).andReturn();
        PLATFORM.take();
        PLATFORM.take();
        assertEquals("/enterprises/" + enterprise + "/workspace", response.getResponse().getHeader("Location"));
        assertEquals("channel", data(mvc.perform(get("/api/v1/auth/me").cookie(response.getResponse().getCookie("SESSION")))
            .andExpect(status().isOk()).andReturn()).path("authenticationMethod").asText());
    }

    private JsonNode bind(Browser browser) throws Exception {
        callback(browser, state(begin(browser)));
        return data(confirm(browser, key()).andExpect(status().isCreated()).andReturn());
    }

    private Browser externalLogin() throws Exception {
        var browser = anonymous();
        var start = data(writeAs(browser, HttpMethod.POST, "/api/v1/auth/channel-login/" + loginKey,
            Map.of("embeddedClient", false), null, key()).andExpect(status().isOk()).andReturn());
        queueIdentity();
        var response = rawCallback(browser, connection, state(start)).andExpect(status().isSeeOther()).andReturn();
        takeIdentityRequests();
        assertEquals("/enterprises/" + enterprise + "/workspace", response.getResponse().getHeader("Location"));
        return browser(response.getResponse().getCookie("SESSION"), member.userId(), browser.source());
    }

    private JsonNode begin(Browser browser) throws Exception {
        return data(writeAs(browser, HttpMethod.POST, channels() + "/" + connection + "/authorize",
            Map.of("embeddedClient", false), null, key()).andExpect(status().isOk()).andReturn());
    }

    private String state(JsonNode start) {
        return UriComponentsBuilder.fromUriString(start.path("authorizationUrl").asText()).build().getQueryParams().getFirst("state");
    }

    private void callback(Browser browser, String state) throws Exception {
        queueIdentity();
        var result = rawCallback(browser, connection, state).andExpect(status().isSeeOther()).andReturn();
        assertEquals("/channel-authorization", result.getResponse().getHeader("Location"));
        takeIdentityRequests();
        reviews.put(browser.cookie().getValue(), flow(state).getId());
    }

    private ResultActions rawCallback(Browser browser, String id, String state) throws Exception {
        return mvc.perform(get("/api/v1/auth/channel-callbacks/" + id).cookie(browser.cookie()).param("state", state).param("code", "test-one-use-code"));
    }

    private ResultActions confirm(Browser browser, String key) throws Exception {
        return writeAs(browser, HttpMethod.POST, channels() + "/confirm", Map.of("authorizationId", reviews.get(browser.cookie().getValue()),
            "receiveEnabled", true, "externalLoginEnabled", true), null, key);
    }

    private ChannelOauthSessionRow flow(String state) {
        return databaseAccess.mapper(ChannelOauthSessionMapper.class).selectOne(new LambdaQueryWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getStateHash, authorizationSecrets.hash(state)));
    }

    private long countBindings() {
        return databaseAccess.mapper(UserChannelBindingMapper.class).selectCount(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getConnectionId, connection));
    }

    private Browser member() throws Exception {
        String username = "channel_" + key().substring(0, 8);
        EnterpriseTestData.member(users, permissions, enterprise, username, "channel-member-password", "授权测试成员",
            List.of(permissions.builtinRoleId(enterprise, "member")));
        return login(anonymous(), username, "channel-member-password");
    }

    private Browser login(Browser before, String username, String password) throws Exception {
        var response = writeAs(before, HttpMethod.POST, "/api/v1/auth/login", Map.of("identifier", username, "password", password), null, key())
            .andExpect(status().isOk()).andReturn();
        return browser(response.getResponse().getCookie("SESSION"), data(response).path("id").asText(), before.source());
    }

    private Browser anonymous() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new Browser(response.getResponse().getCookie("SESSION"), data(response).path("token").asText(), null,
            "203.0.113." + SOURCES.incrementAndGet());
    }

    private Browser browser(Cookie cookie, String user, String source) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
        return new Browser(cookie, data(response).path("token").asText(), user, source);
    }

    private ResultActions writeAs(Browser browser, HttpMethod method, String path, Object body, String revision, String key) throws Exception {
        var request = request(method, path).cookie(browser.cookie()).header("X-CSRF-Token", browser.csrf()).header("Idempotency-Key", key);
        request.with(value -> {
            value.setRemoteAddr(browser.source());
            return value;
        });
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (revision != null) {
            request.header("If-Match", "\"" + revision + "\"");
        }
        return mvc.perform(request);
    }

    private String channels() {
        return base() + "/me/channels";
    }

    private void queueIdentity() {
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.userToken");
        PLATFORM.reply("feishu.identity");
    }

    private void takeIdentityRequests() throws Exception {
        PLATFORM.take();
        PLATFORM.take();
        PLATFORM.take();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动模拟授权平台", failure);
        }
    }

    private record Browser(Cookie cookie, String csrf, String userId, String source) {
    }
}

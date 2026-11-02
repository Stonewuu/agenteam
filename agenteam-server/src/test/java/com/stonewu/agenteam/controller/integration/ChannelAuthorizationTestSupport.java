package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets;
import com.stonewu.agenteam.support.EnterpriseTestData;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.MockChannelServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经正式会话、模拟平台网络和真实数据库验证绑定与外部登录。 */
@Import({SharedEnterpriseTestEdition.class, ChannelAuthorizationTestSupport.PlatformConfiguration.class})
abstract class ChannelAuthorizationTestSupport extends ExecutionApiTestSupport {
    protected static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();
    protected static final MockChannelServer PLATFORM = platform();
    protected static final AtomicInteger SOURCES = new AtomicInteger();
    @Autowired
    protected ChannelAuthorizationSecrets authorizationSecrets;
    protected String connection;
    protected String loginKey;
    protected Browser member;
    protected final Map<String, String> reviews = new ConcurrentHashMap<>();

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
    void prepareAuthorization() throws Exception {
        PLATFORM.reset();
        reviews.clear();
        connection = null;
        loginKey = null;
        member = member();
    }

    protected void configureConnection() throws Exception {
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
    }

    protected void rejectChangedBrowserAccount() throws Exception {
        String state = state(begin(member));
        var loggedIn = login(member, "execution-submission-admin", "执行提交测试所使用的独立完整口令");
        rawCallback(loggedIn, connection, state).andExpect(status().isSeeOther());
        assertEquals(0, PLATFORM.remainingRequests());
        assertEquals(0, countBindings());
    }

    protected void verifyLoginToggleRevokesSession() throws Exception {
        var external = externalLogin();
        change(HttpMethod.PATCH, base() + "/integrations/" + connection, Map.of("loginEnabled", false), "3", key()).andExpect(status().isOk());
        change(HttpMethod.PATCH, base() + "/integrations/" + connection, Map.of("loginEnabled", true), "4", key()).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(external.cookie())).andExpect(status().isUnauthorized());
    }

    protected void verifyLocalReauthentication() throws Exception {
        var external = externalLogin();
        writeAs(external, HttpMethod.POST, "/api/v1/auth/reauthenticate", Map.of("password", "错误密码"), null, key())
            .andExpect(status().isUnprocessableEntity());
        var response = writeAs(external, HttpMethod.POST, "/api/v1/auth/reauthenticate", Map.of("password", "channel-member-password"), null, key())
            .andExpect(status().isOk()).andReturn();
        assertNotEquals(external.cookie().getValue(), response.getResponse().getCookie("SESSION").getValue());
        assertEquals("password", data(response).path("authenticationMethod").asText());
    }

    protected JsonNode bind(Browser browser) throws Exception {
        callback(browser, state(begin(browser)));
        return data(confirm(browser, key()).andExpect(status().isCreated()).andReturn());
    }

    protected Browser externalLogin() throws Exception {
        var browser = anonymous();
        var start = data(writeAs(browser, HttpMethod.POST, "/api/v1/auth/channel-login/" + loginKey,
            Map.of("embeddedClient", false), null, key()).andExpect(status().isOk()).andReturn());
        queueIdentity();
        var response = rawCallback(browser, connection, state(start)).andExpect(status().isSeeOther()).andReturn();
        takeIdentityRequests();
        assertEquals("/enterprises/" + enterprise + "/workspace", response.getResponse().getHeader("Location"));
        return browser(response.getResponse().getCookie("SESSION"), member.userId(), browser.source());
    }

    protected JsonNode begin(Browser browser) throws Exception {
        if (connection == null) {
            configureConnection();
        }
        return data(writeAs(browser, HttpMethod.POST, channels() + "/" + connection + "/authorize",
            Map.of("embeddedClient", false), null, key()).andExpect(status().isOk()).andReturn());
    }

    protected String state(JsonNode start) {
        return UriComponentsBuilder.fromUriString(start.path("authorizationUrl").asText()).build().getQueryParams().getFirst("state");
    }

    protected void callback(Browser browser, String state) throws Exception {
        queueIdentity();
        var result = rawCallback(browser, connection, state).andExpect(status().isSeeOther()).andReturn();
        assertEquals("/channel-authorization", result.getResponse().getHeader("Location"));
        takeIdentityRequests();
        reviews.put(browser.cookie().getValue(), flow(state).getId());
    }

    protected ResultActions rawCallback(Browser browser, String id, String state) throws Exception {
        return mvc.perform(get("/api/v1/auth/channel-callbacks/" + id).cookie(browser.cookie()).param("state", state).param("code", "test-one-use-code"));
    }

    protected ResultActions confirm(Browser browser, String key) throws Exception {
        return writeAs(browser, HttpMethod.POST, channels() + "/confirm", Map.of("authorizationId", reviews.get(browser.cookie().getValue()),
            "receiveEnabled", true, "externalLoginEnabled", true), null, key);
    }

    protected ChannelOauthSessionRow flow(String state) {
        return databaseAccess.mapper(ChannelOauthSessionMapper.class).selectOne(new LambdaQueryWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getStateHash, authorizationSecrets.hash(state)));
    }

    protected long countBindings() {
        return databaseAccess.mapper(UserChannelBindingMapper.class).selectCount(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getConnectionId, connection));
    }

    protected Browser member() throws Exception {
        String username = "channel_" + key().substring(0, 8);
        EnterpriseTestData.member(users, permissions, enterprise, username, "channel-member-password", "授权测试成员",
            List.of(permissions.builtinRoleId(enterprise, "member")));
        return login(anonymous(), username, "channel-member-password");
    }

    protected Browser login(Browser before, String username, String password) throws Exception {
        var response = writeAs(before, HttpMethod.POST, "/api/v1/auth/login", Map.of("identifier", username, "password", password), null, key())
            .andExpect(status().isOk()).andReturn();
        return browser(response.getResponse().getCookie("SESSION"), data(response).path("id").asText(), before.source());
    }

    protected Browser anonymous() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new Browser(response.getResponse().getCookie("SESSION"), data(response).path("token").asText(), null,
            "203.0.113." + SOURCES.incrementAndGet());
    }

    protected Browser browser(Cookie cookie, String user, String source) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
        return new Browser(cookie, data(response).path("token").asText(), user, source);
    }

    protected ResultActions writeAs(Browser browser, HttpMethod method, String path, Object body, String revision, String key) throws Exception {
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

    protected String channels() {
        return base() + "/me/channels";
    }

    protected void queueIdentity() {
        PLATFORM.reply("feishu.token");
        PLATFORM.reply("feishu.userToken");
        PLATFORM.reply("feishu.identity");
    }

    protected void takeIdentityRequests() throws Exception {
        PLATFORM.take();
        PLATFORM.take();
        PLATFORM.take();
    }

    protected String key() {
        return UUID.randomUUID().toString();
    }

    protected static MockChannelServer platform() {
        try {
            return new MockChannelServer();
        } catch (IOException failure) {
            throw new IllegalStateException("无法启动模拟授权平台", failure);
        }
    }

    protected record Browser(Cookie cookie, String csrf, String userId, String source) {
    }
}

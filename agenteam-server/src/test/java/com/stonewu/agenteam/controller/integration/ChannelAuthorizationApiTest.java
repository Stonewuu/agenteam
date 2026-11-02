package com.stonewu.agenteam.controller.integration;


import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseSqlMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 通过平台授权回调验证本人确认、外部登录及会话失效。 */
class ChannelAuthorizationApiTest extends ChannelAuthorizationTestSupport {
    @Test
    void anonymousLoginOptionsOnlyExposeEnabledVerifiedConnectionsInActiveEnterprises() throws Exception {
        configureConnection();
        String firstKey = loginKey;
        configureConnection();
        var mapper = databaseAccess.mapper(EnterpriseIntegrationMapper.class);
        var original = mapper.selectById(connection);
        var options = publicOptions();
        for (String key : List.of(firstKey, loginKey)) {
            var option = options.findValues("key").stream().filter(value -> key.equals(value.asText())).findFirst();
            assertTrue(option.isPresent());
        }
        for (var option : options) {
            schemas.validate("ChannelLoginOption", option);
            assertEquals(Set.of("key", "name", "providerCode", "providerName"), json.convertValue(option, Map.class).keySet());
        }
        var exclusions = List.of(
            new LambdaUpdateWrapper<EnterpriseIntegrationRow>().set(EnterpriseIntegrationRow::getStatus, "disabled"),
            new LambdaUpdateWrapper<EnterpriseIntegrationRow>().set(EnterpriseIntegrationRow::getLoginEnabled, false),
            new LambdaUpdateWrapper<EnterpriseIntegrationRow>().set(EnterpriseIntegrationRow::getStatus, "draft")
                .set(EnterpriseIntegrationRow::getIdentityVerifiedAt, null),
            new LambdaUpdateWrapper<EnterpriseIntegrationRow>().set(EnterpriseIntegrationRow::getStatus, "draft")
                .set(EnterpriseIntegrationRow::getExternalTenantId, null),
            new LambdaUpdateWrapper<EnterpriseIntegrationRow>().set(EnterpriseIntegrationRow::getPurpose, "chat_bot")
                .set(EnterpriseIntegrationRow::getLoginEnabled, false).set(EnterpriseIntegrationRow::getMessagingEnabled, false));
        for (var changes : exclusions) {
            mapper.update(changes.eq(EnterpriseIntegrationRow::getId, connection));
            assertFalse(publicOptions().findValues("key").stream().anyMatch(value -> loginKey.equals(value.asText())));
            mapper.updateById(original);
        }
        var enterprises = databaseAccess.mapper(EnterpriseSqlMapper.class);
        enterprises.update(new LambdaUpdateWrapper<EnterpriseRow>().eq(EnterpriseRow::getId, enterprise).set(EnterpriseRow::getStatus, "disabled"));
        try {
            assertFalse(publicOptions().findValues("key").stream().anyMatch(value -> Set.of(firstKey, loginKey).contains(value.asText())));
        } finally {
            enterprises.update(new LambdaUpdateWrapper<EnterpriseRow>().eq(EnterpriseRow::getId, enterprise).set(EnterpriseRow::getStatus, "active"));
        }
        var browser = anonymous();
        var start = data(writeAs(browser, HttpMethod.POST, "/api/v1/auth/channel-login/" + loginKey,
            Map.of("embeddedClient", false), null, key()).andExpect(status().isOk()).andReturn());
        assertTrue(start.path("authorizationUrl").asText().contains("code_challenge="));
        mapper.update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, connection)
            .set(EnterpriseIntegrationRow::getLoginEnabled, false));
        writeAs(browser, HttpMethod.POST, "/api/v1/auth/channel-login/" + loginKey,
            Map.of("embeddedClient", false), null, key()).andExpect(status().isNotFound());
        mapper.update(new LambdaUpdateWrapper<EnterpriseIntegrationRow>().eq(EnterpriseIntegrationRow::getId, connection)
            .set(EnterpriseIntegrationRow::getStatus, "deleted").set(EnterpriseIntegrationRow::getDeletedAt, Instant.now())
            .set(EnterpriseIntegrationRow::getDeletedToken, connection));
        assertFalse(publicOptions().findValues("key").stream().anyMatch(value -> loginKey.equals(value.asText())));
    }

    private JsonNode publicOptions() throws Exception {
        return data(mvc.perform(get("/api/v1/auth/channel-login")).andExpect(status().isOk()).andReturn());
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
        var otherBrowser = anonymous();
        rawCallback(otherBrowser, connection, state).andExpect(status().isSeeOther());
        assertEquals("pending", flow(state).getStatus());
        rawCallback(member, key(), state).andExpect(status().isSeeOther());
        assertEquals(0, PLATFORM.remainingRequests());
        queueIdentity();
        rawCallback(member, connection, state).andExpect(status().isSeeOther());
        PLATFORM.take();
        var exchange = PLATFORM.take();
        assertEquals("/open-apis/authen/v2/oauth/token", exchange.uri().getPath());
        assertTrue(exchange.header("Content-Type").startsWith("application/json"));
        var exchanged = json.readTree(exchange.body());
        String verifier = exchanged.path("code_verifier").asText();
        assertTrue(verifier.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(query.getFirst("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
        assertEquals(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8), exchanged.path("redirect_uri").asText());
        PLATFORM.take();
        reviews.put(member.cookie().getValue(), flow(state).getId());
        assertEquals(0, countBindings());
        var review = data(mvc.perform(get("/api/v1/auth/channel-authorization").cookie(member.cookie())).andExpect(status().isOk()).andReturn());
        schemas.validate("ChannelAuthorizationReview", review);
        assertEquals("ou_test_member", review.path("externalSubjectId").asText());
        assertEquals(enterprise, review.path("enterpriseId").asText());
        assertTrue(review.path("chatAvailable").asBoolean());
        String requestKey = key();
        var confirmation = Map.of("authorizationId", reviews.get(member.cookie().getValue()), "receiveEnabled", false,
            "externalLoginEnabled", false, "chatEnabled", true);
        var bound = data(writeAs(member, HttpMethod.POST, channels() + "/confirm", confirmation, null, requestKey).andExpect(status().isCreated()).andReturn());
        schemas.validate("ChannelBinding", bound);
        assertTrue(bound.path("chatEnabled").asBoolean());
        assertFalse(bound.path("externalLoginEnabled").asBoolean());
        assertEquals(bound, data(writeAs(member, HttpMethod.POST, channels() + "/confirm", confirmation, null, requestKey).andExpect(status().isCreated()).andReturn()));
        assertEquals(1, countBindings());
        var consumed = flow(state);
        assertEquals("consumed", consumed.getStatus());
        assertNull(consumed.getTransientEncryptedJson());
        assertNull(consumed.getConfirmationTokenHash());
        assertFalse(bound.toString().contains("test-auth-secret"));
        confirm(member, key()).andExpect(status().isConflict());
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
        rejectChangedBrowserAccount();
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
    void anotherTabsCallbackCannotReplaceTheIdentityShownOnConfirmationPage() throws Exception {
        String state = state(begin(member));
        callback(member, state);
        databaseAccess.mapper(ChannelOauthSessionMapper.class).update(new LambdaUpdateWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getId, flow(state).getId())
            .set(ChannelOauthSessionRow::getConfirmationExpiresAt, Instant.now().minusSeconds(1)));
        confirm(member, key()).andExpect(status().isConflict());
        assertEquals(0, countBindings());
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
        verifyLoginToggleRevokesSession();
        verifyLocalReauthentication();
    }

    @Test
    void superAdministratorCanLoginAndRetainManagementAccessUntilBindingIsRevoked() throws Exception {
        var administrator = new Browser(cookie, csrf, admin, "203.0.113.250");
        callback(administrator, state(begin(administrator)));
        var review = data(mvc.perform(get("/api/v1/auth/channel-authorization").cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertTrue(review.path("loginAvailable").asBoolean());
        var bound = data(writeAs(administrator, HttpMethod.POST, channels() + "/confirm",
            Map.of("authorizationId", reviews.get(administrator.cookie().getValue()), "receiveEnabled", true, "externalLoginEnabled", true),
            null, key()).andExpect(status().isCreated()).andReturn());
        assertTrue(bound.path("receiveEnabled").asBoolean());
        assertTrue(bound.path("externalLoginEnabled").asBoolean());
        var external = externalLogin();
        var me = data(mvc.perform(get("/api/v1/auth/me").cookie(external.cookie())).andExpect(status().isOk()).andReturn());
        assertEquals(admin, me.path("id").asText());
        assertTrue(me.path("superAdmin").asBoolean());
        assertEquals("channel", me.path("authenticationMethod").asText());
        assertTrue(me.path("restrictedEnterpriseId").isNull());
        assertFalse(me.path("capabilities").isEmpty());
        mvc.perform(get("/api/v1/system/enterprises/" + enterprise + "/integrations").cookie(external.cookie())).andExpect(status().isOk());
        writeAs(external, HttpMethod.DELETE, channels() + "/" + bound.path("id").asText(), null, "1", key())
            .andExpect(status().isForbidden());
        writeAs(administrator, HttpMethod.DELETE, channels() + "/" + bound.path("id").asText(), null, "1", key())
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(external.cookie())).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wecomApplicationAuthorizationSupportsMembersAndSuperAdministrators(boolean administrator) throws Exception {
        if (administrator) {
            member = new Browser(cookie, csrf, admin, "203.0.113.251");
        }
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
        var loggedInCookie = response.getResponse().getCookie("SESSION");
        var me = data(mvc.perform(get("/api/v1/auth/me").cookie(loggedInCookie)).andExpect(status().isOk()).andReturn());
        assertEquals("channel", me.path("authenticationMethod").asText());
        assertEquals(administrator, me.path("superAdmin").asBoolean());
        mvc.perform(get("/api/v1/system/enterprises/" + enterprise + "/integrations").cookie(loggedInCookie))
            .andExpect(administrator ? status().isOk() : status().isForbidden());
    }

}

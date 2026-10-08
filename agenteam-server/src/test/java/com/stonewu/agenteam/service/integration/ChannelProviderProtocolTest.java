package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelAccessToken;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.integration.provider.feishu.FeishuIntegrationProvider;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomIntegrationProvider;
import com.stonewu.agenteam.support.MockChannelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在网络边界核对官方请求协议与响应样本，不用适配器自己的返回值证明协议正确。
 */
class ChannelProviderProtocolTest {
    private static final URI CALLBACK = URI.create("https://app.example.test/api/v1/auth/channel-callbacks/test");
    private static final URI DETAIL = URI.create("https://app.example.test/notifications/test-notice");
    private static final IntegrationApplication WECOM = new IntegrationApplication("test-wecom", "enterprise-a", "wecom",
        "ww_test_corp", "1000001", 1);
    private static final IntegrationApplication FEISHU = new IntegrationApplication("test-feishu", "enterprise-a", "feishu",
        "test-feishu-tenant", "cli_test_application", 1);
    private final ObjectMapper json = new ObjectMapper();
    private MockChannelServer server;
    private WecomIntegrationProvider wecom;
    private FeishuIntegrationProvider feishu;

    @BeforeEach
    void prepare() throws Exception {
        server = new MockChannelServer();
        var endpoints = new IntegrationEndpoints(server.origin(), server.origin(), server.origin());
        var http = new IntegrationHttpClient(json, Duration.ofSeconds(2));
        var responses = new ChannelResponseMapper();
        wecom = new WecomIntegrationProvider(endpoints, http, responses, json);
        feishu = new FeishuIntegrationProvider(endpoints, http, responses, json);
    }

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void wecomUsesQueryCredentialsAndVerifiesApplicationBeforeAcceptingMemberIdentity() throws Exception {
        server.reply("wecom.token");
        var token = wecom.fetchAccessToken(WECOM, "test-secret+&=");
        assertEquals(3600, token.expiresInSeconds());
        var tokenRequest = server.take();
        assertEquals("GET", tokenRequest.method());
        assertEquals("/cgi-bin/gettoken", tokenRequest.uri().getPath());
        assertEquals(Map.of("corpid", "ww_test_corp", "corpsecret", "test-secret+&="), tokenRequest.query());
        assertNull(tokenRequest.header("Authorization"));

        server.reply("wecom.application");
        assertEquals("ww_test_corp", wecom.checkApplication(WECOM, token).tenantId());
        var applicationRequest = server.take();
        assertEquals("/cgi-bin/agent/get", applicationRequest.uri().getPath());
        assertEquals("1000001", applicationRequest.query().get("agentid"));
        assertEquals(token.value(), applicationRequest.query().get("access_token"));

        server.reply("wecom.identity");
        var identity = wecom.exchangeIdentity(WECOM, "test-secret", token, "test-code", CALLBACK, null);
        assertEquals("member_001", identity.subjectId());
        assertEquals("wecom_userid", identity.subjectType());
        var identityRequest = server.take();
        assertEquals("GET", identityRequest.method());
        assertEquals("/cgi-bin/auth/getuserinfo", identityRequest.uri().getPath());
        assertEquals(Map.of("access_token", token.value(), "code", "test-code"), identityRequest.query());
    }

    @Test
    void feishuApplicationTokenAndTenantUseTheirDocumentedFieldNames() throws Exception {
        server.reply("feishu.token");
        var token = feishu.fetchAccessToken(FEISHU, "test-feishu-secret");
        assertEquals(1800, token.expiresInSeconds());
        var tokenRequest = server.take();
        assertEquals("POST", tokenRequest.method());
        assertEquals("/open-apis/auth/v3/tenant_access_token/internal", tokenRequest.uri().getPath());
        assertTrue(tokenRequest.header("Content-Type").startsWith("application/json"));
        assertEquals(Map.of(), tokenRequest.query());
        assertEquals(json.valueToTree(Map.of("app_id", "cli_test_application", "app_secret", "test-feishu-secret")),
            json.readTree(tokenRequest.body()));

        server.reply("feishu.tenant");
        assertEquals("test-feishu-tenant", feishu.checkApplication(FEISHU, token).tenantId());
        var tenantRequest = server.take();
        assertEquals("GET", tenantRequest.method());
        assertEquals("/open-apis/tenant/v2/tenant/query", tenantRequest.uri().getPath());
        assertEquals("Bearer test-feishu-application-token", tenantRequest.header("Authorization"));
        assertEquals(Map.of(), tenantRequest.query());
    }

    @Test
    void feishuExchangesV3FormThenUsesUserTokenAndChecksTenant() throws Exception {
        server.reply("feishu.userToken");
        server.reply("feishu.identity");
        String verifier = "test-verifier-0123456789012345678901234567890123456789";
        var identity = feishu.exchangeIdentity(FEISHU, "secret+&=中文", token(), "code+&=", CALLBACK, verifier);
        assertEquals("ou_test_member", identity.subjectId());
        assertEquals("feishu_open_id", identity.subjectType());
        var exchange = server.take();
        assertEquals("POST", exchange.method());
        assertEquals("/oauth/v3/token", exchange.uri().getPath());
        assertTrue(exchange.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
        assertEquals(Map.of("grant_type", "authorization_code", "client_id", "cli_test_application", "client_secret",
            "secret+&=中文", "code", "code+&=", "redirect_uri", CALLBACK.toString(), "code_verifier", verifier), exchange.form());
        assertFalse(exchange.body().contains("offline_access"));
        var information = server.take();
        assertEquals("/open-apis/authen/v1/user_info", information.uri().getPath());
        assertEquals("Bearer test-feishu-user-token", information.header("Authorization"));
    }

    @Test
    void anotherTenantExternalContactAndExpiredCodeCannotBecomeLocalMember() {
        server.reply("feishu.userToken");
        server.reply("feishu.identity");
        var otherTenant = new IntegrationApplication("test-feishu", "enterprise-a", "feishu", "another-tenant", "cli_test_application", 1);
        assertEquals("CHANNEL_TENANT_MISMATCH", assertThrows(ChannelProviderException.class,
            () -> feishu.exchangeIdentity(otherTenant, "secret", token(), "code", CALLBACK, "verifier")).code());
        server.reply("wecom.externalIdentity");
        assertEquals("CHANNEL_MEMBER_REQUIRED", assertThrows(ChannelProviderException.class,
            () -> wecom.exchangeIdentity(WECOM, "secret", token(), "code", CALLBACK, null)).code());
        server.reply(400, "feishu.expiredCode", Map.of());
        assertThrows(ChannelProviderException.class,
            () -> feishu.exchangeIdentity(FEISHU, "secret", token(), "expired", CALLBACK, "verifier"));
    }

    @Test
    void authorizationUrlsUseOfficialEntryPointsAndPreserveCallbackAndState() {
        String state = "a123456789012345678901234567890123456789012345678901234567890123";
        var embedded = wecom.authorizationUri(WECOM, CALLBACK, state, null, true);
        assertEquals("open.weixin.qq.com", embedded.getHost());
        assertEquals("/connect/oauth2/authorize", embedded.getPath());
        assertEquals("wechat_redirect", embedded.getFragment());
        assertTrue(embedded.getQuery().contains("scope=snsapi_base"));
        var browser = wecom.authorizationUri(WECOM, CALLBACK, state, null, false);
        assertEquals("login.work.weixin.qq.com", browser.getHost());
        assertEquals("/wwlogin/sso/login", browser.getPath());
        assertTrue(browser.getQuery().contains("login_type=CorpApp"));
        var official = new FeishuIntegrationProvider(new IntegrationEndpoints(), new IntegrationHttpClient(json),
            new ChannelResponseMapper(), json);
        var authorization = official.authorizationUri(FEISHU, CALLBACK, state, "challenge", false);
        assertEquals("accounts.feishu.cn", authorization.getHost());
        assertEquals("/open-apis/authen/v1/authorize", authorization.getPath());
        assertTrue(authorization.getQuery().contains("code_challenge_method=S256"));
        assertFalse(authorization.toString().contains("client_secret"));
        assertFalse(authorization.toString().contains("offline_access"));
    }

    @Test
    void sendingUsesApplicationIdentityAndStableProviderSpecificPayload() throws Exception {
        server.reply("wecom.accepted");
        var wecomPayload = wecom.render(WECOM, "member_001", "提醒", "<内容>\n第二行", DETAIL, "request-001");
        assertEquals(Outcome.ACCEPTED, wecom.send(WECOM, token(), wecomPayload).outcome());
        var wecomRequest = server.take();
        assertEquals("POST", wecomRequest.method());
        assertEquals("/cgi-bin/message/send", wecomRequest.uri().getPath());
        assertEquals(Map.of("access_token", "application-token"), wecomRequest.query());
        assertNull(wecomRequest.header("Authorization"));
        var w = json.readTree(wecomRequest.body());
        assertEquals("member_001", w.path("touser").asText());
        assertEquals(1000001, w.path("agentid").asInt());
        assertEquals("textcard", w.path("msgtype").asText());
        assertEquals(1, w.path("enable_duplicate_check").asInt());
        assertEquals(3600, w.path("duplicate_check_interval").asInt());
        assertEquals("&lt;内容&gt;<br>第二行", w.at("/textcard/description").asText());
        assertEquals(DETAIL.toString(), w.at("/textcard/url").asText());

        server.reply("feishu.accepted");
        var feishuPayload = feishu.render(FEISHU, "ou_test_member", "提醒", "正文", DETAIL, "request-001");
        var accepted = feishu.send(FEISHU, token(), feishuPayload);
        assertEquals(Outcome.ACCEPTED, accepted.outcome());
        assertEquals("om_test_message", accepted.messageId());
        var feishuRequest = server.take();
        assertEquals("/open-apis/im/v1/messages", feishuRequest.uri().getPath());
        assertEquals(Map.of("receive_id_type", "open_id"), feishuRequest.query());
        assertEquals("Bearer application-token", feishuRequest.header("Authorization"));
        var f = json.readTree(feishuRequest.body());
        assertTrue(f.path("content").isTextual());
        assertEquals("提醒\n\n正文\n" + DETAIL, json.readTree(f.path("content").asText()).path("text").asText());
        assertEquals("request-001", f.path("uuid").asText());
        assertEquals(feishuPayload, feishu.render(FEISHU, "ou_test_member", "提醒", "正文", DETAIL, "request-001"));
    }

    @ParameterizedTest
    @CsvSource({
        "wecom, wecom.invalidRecipient, 200, PERMANENT_FAILURE",
        "wecom, wecom.unlicensedRecipient, 200, PERMANENT_FAILURE",
        "wecom, wecom.allInvalid, 200, PERMANENT_FAILURE",
        "wecom, wecom.rateLimit, 200, RETRYABLE_FAILURE",
        "wecom, wecom.expiredToken, 200, TOKEN_EXPIRED",
        "feishu, feishu.unavailableRecipient, 400, PERMANENT_FAILURE",
        "feishu, feishu.permissionDenied, 400, PERMANENT_FAILURE",
        "feishu, feishu.userDisabledMessages, 400, PERMANENT_FAILURE",
        "feishu, feishu.rateLimit, 429, RETRYABLE_FAILURE",
        "feishu, feishu.rateLimit, 400, RETRYABLE_FAILURE",
        "feishu, feishu.expiredToken, 401, TOKEN_EXPIRED"
    })
    void officialBusinessErrorsAreNotConvertedToSuccess(String provider, String fixture, int status, Outcome expected) {
        server.reply(status, fixture, Map.of("x-ogw-ratelimit-reset", "52"));
        ChannelMessageSender sender = provider.equals("wecom") ? wecom : feishu;
        var result = sender.send(provider.equals("wecom") ? WECOM : FEISHU, token(), json.createObjectNode());
        assertEquals(expected, result.outcome());
        assertEquals(52L, result.retryAfterSeconds());
        assertNull(result.messageId());
    }

    @Test
    void ambiguousFailuresNeverRetryAutomaticallyOrClaimAcceptance() throws Exception {
        server.disconnect();
        assertEquals(Outcome.UNKNOWN, feishu.send(FEISHU, token(), json.createObjectNode()).outcome());
        server.take();
        assertEquals(0, server.remainingRequests());
        server.raw(503, "{}", Map.of());
        assertEquals(Outcome.UNKNOWN, wecom.send(WECOM, token(), json.createObjectNode()).outcome());
        server.raw(200, "invalid-json", Map.of());
        assertEquals(Outcome.UNKNOWN, feishu.send(FEISHU, token(), json.createObjectNode()).outcome());
        server.raw(302, "{}", Map.of("Location", server.origin() + "/must-not-follow"));
        assertEquals(Outcome.UNKNOWN, feishu.send(FEISHU, token(), json.createObjectNode()).outcome());
        assertEquals(3, server.remainingRequests());
    }

    @Test
    void tokenDiagnosticsAndExceptionCausesDoNotExposeCredentials() {
        var original = new IllegalStateException("https://example.test?secret=test-sensitive-value");
        var wrapped = new ChannelProviderException("CHANNEL_TEST", "测试脱敏", original);
        assertFalse(wrapped.getCause().toString().contains("test-sensitive-value"));
        assertArrayEquals(original.getStackTrace(), wrapped.getCause().getStackTrace());
        assertFalse(new ChannelAccessToken("test-sensitive-value", 30).toString().contains("test-sensitive-value"));
    }

    @Test
    void wecomCardBoundsEscapedDescriptionWithoutTurningARecipientIntoBroadcast() {
        var payload = wecom.render(WECOM, "member_001", "提醒", "<".repeat(500), DETAIL, "request-002");
        String summary = payload.at("/textcard/description").asText();
        assertTrue(summary.length() <= 512);
        assertTrue(summary.endsWith("&lt;…"));
        assertThrows(ChannelProviderException.class, () -> wecom.render(WECOM, "@all", "提醒", "正文", DETAIL, "request-003"));
        assertThrows(ChannelProviderException.class, () -> wecom.render(WECOM, "one|two", "提醒", "正文", DETAIL, "request-004"));
    }

    @Test
    void feishuStillProcessingIsUnknownAndMalformedSuccessDoesNotCreateMessageNumber() {
        server.raw(400, "{\"code\":230049,\"msg\":\"The message is being sent.\"}", Map.of());
        assertEquals(Outcome.UNKNOWN, feishu.send(FEISHU, token(), json.createObjectNode()).outcome());
        server.raw(200, "{\"code\":0,\"data\":{}}", Map.of());
        assertEquals(Outcome.UNKNOWN, feishu.send(FEISHU, token(), json.createObjectNode()).outcome());
        server.raw(200, "{\"code\":\"0\",\"data\":{\"message_id\":\"invalid\"}}", Map.of());
        assertEquals(Outcome.UNKNOWN, feishu.send(FEISHU, token(), json.createObjectNode()).outcome());
    }

    private ChannelAccessToken token() {
        return new ChannelAccessToken("application-token", 3600);
    }
}

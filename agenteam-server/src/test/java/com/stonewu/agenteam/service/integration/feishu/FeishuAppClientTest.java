package com.stonewu.agenteam.service.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.support.MockChannelServer;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class FeishuAppClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void registrationUsesOfficialFormAndKeepsPollingSeparateFromCredentialResult() throws Exception {
        try (var server = new MockChannelServer()) {
            var client = client(server);
            server.raw(200, """
                {"device_code":"device-private","verification_uri_complete":"https://open.feishu.cn/page/launcher?user_code=short",
                 "interval":7,"expires_in":480}
                """, Map.of());
            var started = client.begin("job", "management", "管理应用", "管理机器人");
            var request = server.take();
            assertEquals("/oauth/v1/app/registration", request.uri().getPath());
            assertTrue(request.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
            assertTrue(request.body().contains("action=begin"));
            assertEquals(7, started.interval());
            assertEquals(480, started.expiresIn());
            String addons = query(started.material().url(), "addons");
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(addons)))) {
                var config = json.readTree(gzip.readAllBytes());
                assertFalse(config.path("preset").asBoolean());
                assertTrue(config.path("scopes").path("user").toString().contains("offline_access"));
                assertTrue(config.path("scopes").path("user").toString().contains(FeishuAppClient.MANAGE_SCOPE));
            }
            assertEquals("true", query(started.material().url(), "createOnly"));
            assertEquals("管理应用", query(started.material().url(), "name"));
            server.raw(400, "{\"error\":\"authorization_pending\"}", Map.of());
            assertEquals("authorization_pending", client.poll("job", started.material().deviceCode()).state());
            assertTrue(server.take().body().contains("device_code=device-private"));
            server.raw(200, "{\"client_id\":\"cli_test123\",\"client_secret\":\"private-secret\"}", Map.of());
            var created = client.poll("job", started.material().deviceCode());
            assertEquals("created", created.state());
            assertEquals("private-secret", created.secret());
            assertFalse(created.toString().contains("private-secret"));
        }
    }

    @Test
    void configurationUsesBothLongConnectionsAndTokenExchangeKeepsPkceOnVersionTwo() throws Exception {
        try (var server = new MockChannelServer()) {
            var client = client(server);
            var config = client.configuration("bot", URI.create("http://localhost:3000/callback"));
            assertEquals("websocket", config.at("/event/subscription_type").asText());
            assertEquals("websocket", config.at("/callback/callback_type").asText());
            assertEquals("card.action.trigger", config.at("/callback/add_callbacks/0").asText());
            assertEquals(3, config.at("/event/add_events").size());
            server.raw(200, "{\"code\":0,\"access_token\":\"private-access\",\"expires_in\":7200}", Map.of());
            client.exchange("flow", "cli_test", "private-secret", "one-time-code", URI.create("http://localhost:3000/callback"), "verifier");
            var exchange = server.take();
            assertEquals("/open-apis/authen/v2/oauth/token", exchange.uri().getPath());
            assertEquals("verifier", json.readTree(exchange.body()).path("code_verifier").asText());
            server.raw(200, "{\"code\":0,\"data\":{\"app_version\":{\"version\":\"1.0.1\",\"status\":1}}}", Map.of());
            client.version("job", "cli_test", "version1", "access");
            assertEquals("zh_cn", server.take().query().get("lang"));
            assertEquals("1.0.10", FeishuSetupProcessor.nextVersion("1.0.9"));
            assertThrows(ChannelProviderException.class, () -> FeishuAppClient.allowedLink("http://open.feishu.cn/create"));
            assertThrows(ChannelProviderException.class, () -> FeishuAppClient.allowedLink("https://open.feishu.cn.evil.test/create"));
            assertThrows(ChannelProviderException.class, () -> FeishuAppClient.allowedLink("https://user:password@open.feishu.cn/create"));
        }
    }

    private FeishuAppClient client(MockChannelServer server) {
        return new FeishuAppClient(new IntegrationHttpClient(json), new IntegrationEndpoints(server.origin(), server.origin(), server.origin()),
            new ChannelResponseMapper(), json);
    }

    @Test
    void enterpriseRequestsAllSupportedCapabilitiesTogetherAndKeepsBotEventsSeparate() throws Exception {
        try (var server = new MockChannelServer()) {
            var client = client(server);
            server.raw(200, "{\"device_code\":\"device\",\"verification_uri_complete\":\"https://open.feishu.cn/page/launcher?user_code=test\"}", Map.of());
            var started = client.begin("job", "enterprise", "企业接入", "企业连接");
            String addons = query(started.material().url(), "addons");
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(addons)))) {
                var template = json.readTree(gzip.readAllBytes());
                assertTrue(template.at("/scopes/tenant").toString().contains("im:message:send_as_bot"));
                assertTrue(template.at("/scopes/tenant").toString().contains("application:application:self_manage"));
                assertTrue(template.at("/scopes/user").toString().contains(FeishuAppClient.MANAGE_SCOPE));
                assertTrue(template.at("/scopes/user").toString().contains("offline_access"));
            }
            var config = client.configuration("enterprise", URI.create("http://localhost:3000/identity"), URI.create("http://localhost:3000/manage"));
            assertEquals(2, config.at("/security/add/redirect_urls").size());
            assertTrue(config.at("/security/allow_refresh_token").asBoolean());
            assertFalse(config.has("event"));
            assertFalse(config.has("callback"));
            server.raw(200, "{\"code\":0,\"data\":{\"scopes\":[]}}", Map.of());
            var missing = assertThrows(ChannelProviderException.class, () -> client.requireSelfManagement("old-app", "token"));
            assertEquals("FEISHU_SELF_MANAGEMENT_REQUIRED", missing.code());
        }
    }

    private String query(String url, String name) {
        for (String part : URI.create(url).getRawQuery().split("&")) {
            String[] entry = part.split("=", 2);
            if (entry[0].equals(name)) {
                return URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("没有找到创建链接参数：" + name);
    }
}

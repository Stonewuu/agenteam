package com.stonewu.agenteam.service.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.support.MockChannelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ChannelRequestDiagnosticsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(IntegrationHttpClient.class);
    private MockChannelServer server;
    private IntegrationHttpClient http;

    @BeforeEach
    void prepare() throws Exception {
        server = new MockChannelServer();
        http = new IntegrationHttpClient(json, Duration.ofSeconds(2));
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void close() {
        logger.detachAppender(events);
        events.stop();
        server.close();
    }

    @Test
    void logsWecomBusinessFailureOnHttpSuccessAndRemovesQuerySecrets() {
        String secret = "private-credential+&=中文";
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        server.raw(200, json.createObjectNode().put("errcode", 60020)
            .put("errmsg", "not allow to access from your ip 203.0.113.7; " + secret + " " + encoded).toString(), Map.of());
        http.get("test-wecom", server.origin().resolve("/cgi-bin/gettoken"), Map.of("corpsecret", secret), null);
        String logs = logs();
        assertTrue(logs.contains("HTTP状态=200"));
        assertTrue(logs.contains("平台错误码=60020"));
        assertTrue(logs.contains("not allow to access from your ip 203.0.113.7"));
        assertTrue(logs.contains("路径=/cgi-bin/gettoken"));
        assertFalse(logs.contains(secret));
        assertFalse(logs.contains(encoded));
    }

    @Test
    void logsFeishuPermissionReasonAndTraceWithoutRequestOrResponseTokens() {
        server.raw(403, """
            {"code":99991672,"msg":"Access denied. im:message:send_as_bot permission required. Bearer private-bearer response-token",
             "tenant_access_token":"response-token"}
            """, Map.of("X-Tt-Logid", "test-trace-20260928"));
        http.post("test-feishu", server.origin().resolve("/open-apis/im/v1/messages"), Map.of(),
            json.createObjectNode().put("app_secret", "private-app-secret"), "private-bearer");
        String logs = logs();
        assertTrue(logs.contains("平台错误码=99991672"));
        assertTrue(logs.contains("im:message:send_as_bot permission required"));
        assertTrue(logs.contains("平台排障编号=test-trace-20260928"));
        assertFalse(logs.contains("private-bearer"));
        assertFalse(logs.contains("response-token"));
        assertFalse(logs.contains("private-app-secret"));
    }

    @Test
    void oauthFormFailureKeepsDescriptionButNeverAuthorizationCodeOrVerifier() {
        server.raw(400, """
            {"error":"invalid_grant","error_description":"Authorization code expired: private-code, verifier=private-verifier\\nforged entry"}
            """, Map.of());
        http.form("test-feishu", server.origin().resolve("/oauth/v3/token"),
            Map.of("client_secret", "private-secret", "code", "private-code", "code_verifier", "private-verifier"));
        String logs = logs();
        assertTrue(logs.contains("invalid_grant"));
        assertTrue(logs.contains("Authorization code expired"));
        assertFalse(logs.contains("private-code"));
        assertFalse(logs.contains("private-verifier"));
        assertFalse(logs.contains("\nforged entry"));
    }

    @Test
    void invalidRecipientsAreVisibleEvenWhenWecomReturnsZeroWithoutLoggingMemberIds() {
        server.raw(200, "{\"errcode\":0,\"errmsg\":\"ok\",\"invaliduser\":\"private-member\"}", Map.of());
        http.post("test-wecom", server.origin().resolve("/cgi-bin/message/send"), Map.of(), json.createObjectNode(), null);
        assertTrue(logs().contains("平台拒绝的接收对象字段：invaliduser"));
        assertFalse(logs().contains("private-member"));
    }

    @Test
    void malformedResponseKeepsStackAndHttpStatusWithoutDumpingItsBody() {
        server.raw(502, "<html>proxy failure private-response-token</html>", Map.of("X-Tt-Logid", "test-proxy-trace"));
        http.get("test-wecom", server.origin().resolve("/cgi-bin/gettoken"), Map.of(), null);
        assertTrue(logs().contains("HTTP状态=502"));
        assertTrue(logs().contains("test-proxy-trace"));
        assertTrue(logs().contains("JsonParseException"));
        assertTrue(events.list.stream().anyMatch(event -> event.getThrowableProxy() != null));
        assertFalse(logs().contains("private-response-token"));
    }

    @Test
    void disconnectedRequestRetainsNetworkCauseAndStackWithoutItsQuery() {
        server.disconnect();
        assertThrows(ChannelProviderException.class, () -> http.get("test-wecom", server.origin().resolve("/cgi-bin/gettoken"),
            Map.of("access_token", "private-network-token"), null));
        assertTrue(logs().contains("unexpected end of stream"));
        assertTrue(logs().contains("IOException"));
        assertFalse(logs().contains("private-network-token"));
    }

    @Test
    void echoedNotificationContentIsHiddenAndSuccessfulResponsesDoNotAddWarnings() {
        String privateText = "内部业务通知正文不应出现在日志中";
        server.raw(400, json.createObjectNode().put("code", 230022).put("msg", "Invalid content: " + privateText).toString(), Map.of());
        http.post("test-feishu", server.origin().resolve("/open-apis/im/v1/messages"), Map.of(),
            json.createObjectNode().put("content", json.createObjectNode().put("text", privateText).toString()), null);
        assertTrue(logs().contains("Invalid content"));
        assertFalse(logs().contains(privateText));
        events.list.clear();
        server.reply("feishu.token");
        http.post("test-feishu", server.origin().resolve("/open-apis/auth/v3/tenant_access_token/internal"), Map.of(), json.createObjectNode(), null);
        assertTrue(events.list.isEmpty());
    }

    private String logs() {
        return events.list.stream().map(event -> event.getFormattedMessage()
            + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
            .collect(Collectors.joining("\n"));
    }
}

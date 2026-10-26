package com.stonewu.agenteam.service.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.support.MockChannelServer;
import com.stonewu.agenteam.service.chat.ChatPlatformAdapter.UploadFile;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.ByteArrayInputStream;
import java.io.IOException;

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
    void cardUpdateUsesPatchAndLogsPlatformFailureWithoutCardBody() {
        server.raw(403, "{\"code\":99991672,\"msg\":\"Access denied. permission required\"}", Map.of("X-Tt-Logid", "card-update-trace"));
        http.patch("test-card-update", server.origin().resolve("/open-apis/im/v1/messages/om_test"),
            json.createObjectNode().put("content", "private-card-content"), "private-card-token");
        String output = logs();
        assertTrue(output.contains("PATCH"));
        assertTrue(output.contains("99991672"));
        assertTrue(output.contains("permission required"));
        assertTrue(output.contains("card-update-trace"));
        assertFalse(output.contains("private-card-content"));
        assertFalse(output.contains("private-card-token"));
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
    void oauthFailureKeepsDescriptionButNeverAuthorizationCodeOrVerifier() {
        server.raw(400, """
            {"error":"invalid_grant","error_description":"Authorization code expired: private-code, verifier=private-verifier\\nforged entry"}
            """, Map.of());
        var fields = Map.of("client_secret", "private-secret", "code", "private-code", "code_verifier", "private-verifier");
        http.post("test-feishu", server.origin().resolve("/open-apis/authen/v2/oauth/token"), Map.of(), json.valueToTree(fields), null);
        String logs = logs();
        assertTrue(logs.contains("invalid_grant"));
        assertTrue(logs.contains("Authorization code expired"));
        assertFalse(logs.contains("private-code"));
        assertFalse(logs.contains("private-verifier"));
        assertFalse(logs.contains("private-secret"));
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

    @Test
    void registrationPendingIsQuietAndFailureNeverEchoesRegistrationMaterials() {
        server.raw(400, "{\"error\":\"authorization_pending\"}", Map.of());
        http.postForm("registration-test", server.origin().resolve("/oauth/v1/app/registration"),
            Map.of("device_code", "private-device"), Set.of("authorization_pending"));
        assertTrue(events.list.isEmpty());
        server.raw(400, json.createObjectNode().put("code", 40001).put("msg",
            "invalid request private-device private-secret https://open.feishu.cn/page?user_code=private-code")
            .put("client_secret", "private-secret").put("verification_uri_complete", "https://open.feishu.cn/page?user_code=private-code").toString(), Map.of());
        http.postForm("registration-test", server.origin().resolve("/oauth/v1/app/registration"),
            Map.of("device_code", "private-device"), Set.of());
        assertTrue(logs().contains("invalid request"));
        assertFalse(logs().contains("private-device"));
        assertFalse(logs().contains("private-secret"));
        assertFalse(logs().contains("private-code"));
    }

    @Test
    void uploadedFileEchoesAreHiddenWithoutHidingThePermissionReason() {
        String line = "示例员工甲,20,保密测试工资";
        byte[] content = ("员工,天数,备注\n" + line + "\n").getBytes(StandardCharsets.UTF_8);
        server.raw(403, json.createObjectNode().put("code", 99991672)
            .put("msg", "im:resource:upload permission required; file 私有考勤.csv; content=" + line + "; Bearer upload-private-token").toString(),
            Map.of("X-Tt-Logid", "upload-denied-trace"));
        var client = new IntegrationResourceHttpClient(json, null, new ChannelResponseMapper());
        assertThrows(ChannelProviderException.class, () -> client.upload("file-test", server.origin().resolve("/open-apis/im/v1/files"),
            "upload-private-token", new UploadFile("私有考勤.csv", "text/csv", content.length, () -> new ByteArrayInputStream(content)), () -> {
            }));
        assertTrue(logs().contains("im:resource:upload permission required"));
        assertTrue(logs().contains("HTTP状态=403"));
        assertTrue(logs().contains("upload-denied-trace"));
        assertFalse(logs().contains(line));
        assertFalse(logs().contains("私有考勤.csv"));
        assertFalse(logs().contains("upload-private-token"));
    }

    @Test
    void websocketNetworkFailurePreservesCauseWithoutConnectionQueryTickets() {
        var safe = ChannelRequestDiagnostics.networkCause(new IOException("connection timed out at wss://open.example.test/ws?ticket=private-ticket&device=private-device"));
        assertTrue(safe.getMessage().contains("connection timed out"));
        assertTrue(safe.getMessage().contains("wss://open.example.test/ws?"));
        assertFalse(safe.getMessage().contains("private-ticket"));
        assertFalse(safe.getMessage().contains("private-device"));
        assertTrue(safe.getStackTrace().length > 0);
    }

    @Test
    void encodedWebsocketFileEchoesAreHiddenAfterThePlatformDecodesTheChunk() {
        String content = "测试员工,考勤,工资\n保密测试员工,20,9876.54\n";
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        var request = json.createObjectNode().put("upload_id", "test-upload").put("base64_data", encoded);
        var response = json.createObjectNode().put("errcode", 45009)
            .put("errmsg", "上传暂时限流；回显行：保密测试员工,20,9876.54");
        response.putObject("headers").put("req_id", "test-chunk-trace");
        new IntegrationFrameDiagnostics(json).response("test-file", "aibot_upload_media_chunk", request, response);
        assertTrue(logs().contains("上传暂时限流"));
        assertTrue(logs().contains("45009"));
        assertTrue(logs().contains("test-chunk-trace"));
        assertFalse(logs().contains("保密测试员工"));
        assertFalse(logs().contains("9876.54"));
        assertFalse(logs().contains(encoded));
    }
}

package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.lark.oapi.google.protobuf.ByteString;
import com.lark.oapi.ws.pb.Pbbp2;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuFrameCodec;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuConnectionProvider;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuWebhookVerifier;
import com.stonewu.agenteam.service.chat.provider.wecom.WecomAiBotConnectionProvider;
import com.stonewu.agenteam.service.integration.IntegrationFrameDiagnostics;
import com.stonewu.agenteam.service.integration.WecomResourceDownloader;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomAiBotIntegrationProvider;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用官方协议结构验证分片、签名、认证回执和服务端断开，不接触真实平台。 */
class ChatProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-13T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final String token = "test-only-verification-token";
    private final String key = "test-only-encryption-key";

    @Test
    void dedicatedWecomCredentialsNeverInventATenantOrUseLegacyAccessTokens() {
        var connections = mock(WecomAiBotConnectionProvider.class);
        var connection = mock(ChatConnectionProvider.Connection.class);
        var applications = mock(ChatBotBindingMapper.class);
        when(connections.open(any(), anyString(), any())).thenReturn(connection);
        when(connection.ready()).thenReturn(true);
        var provider = new WecomAiBotIntegrationProvider(connections, applications, clock);
        var application = new IntegrationApplication("connection", "enterprise", "wecom_aibot", null, "bot-id", 1);
        assertNull(provider.verify(application, "test-only-secret").tenantId());
        verify(connection).close();
        assertEquals("CHANNEL_CAPABILITY_UNAVAILABLE", assertThrows(ChannelProviderException.class,
            () -> provider.fetchAccessToken(application, "test-only-secret")).code());
        assertTrue(provider.publicFields().stream().noneMatch(field -> field.name().equals("externalTenantId")));
    }

    @Test
    void feishuFragmentsAreOrderedAndDuplicatePartsDoNotDuplicateContent() throws Exception {
        var codec = new FeishuFrameCodec();
        assertNull(codec.payload(frame(3, 2, "c"), now));
        assertNull(codec.payload(frame(3, 2, "c"), now));
        assertNull(codec.payload(frame(3, 0, "a"), now));
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), codec.payload(frame(3, 1, "b"), now));
        var ack = Pbbp2.Frame.parseFrom(FeishuFrameCodec.acknowledge(frame(1, 0, "a"), 500, 3));
        assertEquals(93, ack.getSeqID());
        assertEquals(500, json.readTree(ack.getPayload().toByteArray()).path("code").asInt());
        assertEquals("3", FeishuFrameCodec.header(ack, "biz_rt"));
        assertEquals("ping", FeishuFrameCodec.header(Pbbp2.Frame.parseFrom(FeishuFrameCodec.ping(87)), "type"));
    }

    @Test
    void inconsistentOrExpiredFragmentsCannotBeCombined() {
        var codec = new FeishuFrameCodec();
        assertNull(codec.payload(frame(2, 0, "a"), now));
        assertThrows(IllegalArgumentException.class, () -> codec.payload(frame(2, 0, "changed"), now));
        assertNull(codec.payload(frame(2, 0, "a"), now));
        assertNull(codec.payload(frame(2, 1, "b"), now.plusSeconds(31)));
        assertThrows(IllegalArgumentException.class, () -> codec.payload(frame(33, 0, "a"), now));
        assertThrows(IllegalArgumentException.class, () -> codec.payload(frame(1, 1, "a"), now));
    }

    @Test
    void verifiedWebhookAuthenticatesOriginalBytesAndRejectsReplayOrWrongToken() throws Exception {
        var verifier = new FeishuWebhookVerifier(clock);
        var event = json.createObjectNode();
        event.putObject("header").put("token", token).put("event_type", "im.message.receive_v1");
        byte[] raw = encrypt(event);
        String timestamp = Long.toString(now.getEpochSecond());
        String signature = sign(raw, timestamp);
        assertEquals(event, verifier.verify(raw, timestamp, "nonce", signature, token, key));
        assertThrows(ApiException.class, () -> verifier.verify(raw, timestamp, "changed", signature, token, key));
        assertThrows(ApiException.class, () -> verifier.verify(raw, timestamp, "nonce", signature, "other-token", key));
        assertThrows(ApiException.class, () -> verifier.verify(raw, Long.toString(now.minusSeconds(301).getEpochSecond()), "nonce", signature, token, key));
        assertThrows(ApiException.class, () -> verifier.verify(event.toString().getBytes(StandardCharsets.UTF_8), timestamp, "nonce", signature, token, key));
        assertThrows(ApiException.class, () -> verifier.verify(raw, timestamp, "nonce", "0".repeat(64), token, key));
    }

    @Test
    void encryptedChallengeStillRequiresItsToken() throws Exception {
        var verifier = new FeishuWebhookVerifier(clock);
        byte[] raw = encrypt(json.valueToTree(Map.of("token", token, "type", "url_verification", "challenge", "test-challenge")));
        assertEquals("test-challenge", verifier.verify(raw, null, null, null, token, key).path("challenge").asText());
        assertThrows(ApiException.class, () -> verifier.verify(raw, null, null, null, "wrong", key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ws://open.feishu.cn", "wss://open.feishu.cn.evil.test", "wss://127.0.0.1", "wss://secret@open.feishu.cn", "wss://open.feishu.cn:8443"})
    void connectionEndpointCannotRedirectCredentialsToAnotherHost(String uri) {
        assertThrows(RuntimeException.class, () -> FeishuConnectionProvider.address(uri));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.1.2.3", "169.254.169.254", "100.100.100.200", "172.16.1.2", "192.168.1.2", "::1", "fd00::1", "2001:db8::1"})
    void resourceDownloadRejectsInternalAndReservedAddresses(String address) throws Exception {
        assertFalse(WecomResourceDownloader.publicAddress(InetAddress.getByName(address)));
    }

    @Test
    void wecomAttachmentPaddingUsesThirtyTwoByteBlocks() throws Exception {
        byte[] source = "真实考勤文件的测试内容".getBytes(StandardCharsets.UTF_8);
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 17);
        int padding = 32 - source.length % 32;
        byte[] plain = Arrays.copyOf(source, source.length + padding);
        Arrays.fill(plain, source.length, plain.length, (byte) padding);
        var cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(Arrays.copyOf(secret, 16)));
        byte[] encrypted = cipher.doFinal(plain);
        assertArrayEquals(source, WecomResourceDownloader.decrypt(encrypted, Base64.getEncoder().encodeToString(secret)));
        plain[plain.length - 1] = 0;
        byte[] invalid = cipher.doFinal(plain);
        assertThrows(IllegalArgumentException.class, () -> WecomResourceDownloader.decrypt(invalid, Base64.getEncoder().encodeToString(secret)));
    }

    @Test
    void wecomAuthenticationReceiptMessagingHeartbeatAndReplacementUseActualProtocol() throws Exception {
        var client = mock(HttpClient.class);
        var builder = mock(WebSocket.Builder.class);
        var socket = mock(WebSocket.class);
        var listener = new AtomicReference<WebSocket.Listener>();
        var sent = new ArrayList<JsonNode>();
        var received = new ArrayList<JsonNode>();
        when(client.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(1));
            listener.get().onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendText(anyString(), eq(true))).thenAnswer(invocation -> {
            var frame = json.readTree(invocation.<String>getArgument(0));
            sent.add(frame);
            listener.get().onText(socket, json.valueToTree(Map.of("headers", Map.of("req_id", frame.path("headers").path("req_id").asText()),
                "errcode", 0, "errmsg", "ok")).toString(), true);
            return CompletableFuture.completedFuture(socket);
        });
        var provider = new WecomAiBotConnectionProvider(client, json, new IntegrationFrameDiagnostics(json), clock);
        try (var connection = provider.open(new IntegrationApplication("connection", "enterprise", "wecom_aibot", null, "bot-id", 1), "test-only-secret", received::add)) {
            assertTrue(connection.ready());
            assertEquals("aibot_subscribe", sent.getFirst().path("cmd").asText());
            assertEquals("bot-id", sent.getFirst().path("body").path("bot_id").asText());
            var check = new ArrayList<String>();
            var result = connection.request("send-test", "aibot_send_msg", json.valueToTree(Map.of("chatid", "test-chat")), () -> check.add("authorized"));
            assertEquals(0, result.path("errcode").asInt());
            assertEquals(1, check.size());
            connection.tick(now.plusSeconds(31));
            assertEquals("ping", sent.getLast().path("cmd").asText());
            String event = "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"event-one\"},\"body\":{\"msgid\":\"one\"}}";
            listener.get().onText(socket, event, true);
            assertEquals(1, received.size());
            int before = sent.size();
            listener.get().onText(socket, "{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\"replaced\"},\"body\":{\"event\":{\"eventtype\":\"disconnected_event\"}}}", true);
            assertTrue(connection.replaced());
            assertFalse(connection.ready());
            assertEquals(2, received.size());
            assertEquals(before, sent.size());
            verify(socket, atLeastOnce()).abort();
        }
    }

    private Pbbp2.Frame frame(int sum, int seq, String content) {
        return Pbbp2.Frame.newBuilder().setSeqID(93).setLogID(42).setService(87).setMethod(1)
            .addHeaders(header("type", "event")).addHeaders(header("message_id", "message"))
            .addHeaders(header("sum", Integer.toString(sum))).addHeaders(header("seq", Integer.toString(seq)))
            .setPayload(ByteString.copyFromUtf8(content)).build();
    }

    private Pbbp2.Header header(String name, String value) {
        return Pbbp2.Header.newBuilder().setKey(name).setValue(value).build();
    }

    private byte[] encrypt(JsonNode plain) throws Exception {
        byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) 7);
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)), "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8));
        byte[] combined = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return json.writeValueAsBytes(Map.of("encrypt", Base64.getEncoder().encodeToString(combined)));
    }

    private String sign(byte[] body, String timestamp) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update((timestamp + "nonce" + key).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(body));
    }
}

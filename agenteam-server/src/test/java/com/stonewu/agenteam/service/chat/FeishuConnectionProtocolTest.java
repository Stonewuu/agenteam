package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.google.protobuf.ByteString;
import com.lark.oapi.ws.pb.Pbbp2;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuConnectionProvider;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuFrameCodec;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 从正式长连接帧经过官方事件分派，验证所有事件都在保存成功后才得到成功回执。 */
class FeishuConnectionProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-13T12:00:00Z");
    private final AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
    private final ArrayList<Pbbp2.Frame> sent = new ArrayList<>();
    private WebSocket socket;
    private FeishuConnectionProvider provider;

    @BeforeEach
    void socketFixture() throws Exception {
        var client = mock(HttpClient.class);
        var builder = mock(WebSocket.Builder.class);
        socket = mock(WebSocket.class);
        when(client.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(1));
            listener.get().onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        });
        when(socket.sendBinary(any(), eq(true))).thenAnswer(invocation -> {
            ByteBuffer data = invocation.getArgument(0);
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sent.add(Pbbp2.Frame.parseFrom(bytes));
            return CompletableFuture.completedFuture(socket);
        });
        var responses = mock(ChannelResponseMapper.class);
        when(responses.requireSuccess(any(), eq(true))).thenReturn(json.readTree(
            "{\"data\":{\"URL\":\"wss://msg-frontier.feishu.cn/ws?service_id=87\",\"ClientConfig\":{\"PingInterval\":30}}}"));
        provider = new FeishuConnectionProvider(mock(IntegrationHttpClient.class), new IntegrationEndpoints(), responses,
            json, client, Clock.fixed(now, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"im.message.receive_v1", "im.chat.member.bot.added_v1", "im.chat.member.bot.deleted_v1",
        "im.chat.disbanded_v1", "im.message.recalled_v1", "im.message.reaction.created_v1"})
    void sdkDispatchesEachEventTypeBeforeAcknowledgingIt(String type) throws Exception {
        var received = new ArrayList<JsonNode>();
        try (var connection = open(event -> {
            assertTrue(sent.isEmpty(), "持久接收调用完成前不能发送成功回执");
            received.add(event);
        })) {
            assertTrue(connection.ready());
            receive(frame(1, 0, event(type)));
            assertEquals(1, received.size());
            assertEquals(type, received.getFirst().at("/header/event_type").asText());
            assertEquals(200, json.readTree(sent.getFirst().getPayload().toByteArray()).path("code").asInt());
        }
        verify(socket, atLeastOnce()).abort();
    }

    @Test
    void persistenceFailureReturnsRetryableAcknowledgement() throws Exception {
        try (var connection = open(event -> {
            throw new IllegalStateException("模拟接收事务未提交");
        })) {
            receive(frame(1, 0, event("im.message.receive_v1")));
            assertTrue(connection.ready());
            assertEquals(500, json.readTree(sent.getFirst().getPayload().toByteArray()).path("code").asInt());
        }
    }

    @Test
    void partialEventIsNotDispatchedOrAcknowledgedUntilEveryPartArrives() {
        var received = new ArrayList<JsonNode>();
        String event = event("im.message.receive_v1");
        int middle = event.length() / 2;
        try (var connection = open(received::add)) {
            receive(frame(2, 1, event.substring(middle)));
            assertTrue(received.isEmpty());
            assertTrue(sent.isEmpty());
            receive(frame(2, 0, event.substring(0, middle)));
            assertEquals(1, received.size());
            assertEquals(1, sent.size());
            connection.tick(now);
            assertEquals("ping", FeishuFrameCodec.header(sent.getLast(), "type"));
            connection.tick(now.plusSeconds(71));
            assertFalse(connection.ready(), "超过心跳窗口应释放连接并交由连接管理器重连");
        }
    }

    private ChatConnectionProvider.Connection open(Consumer<JsonNode> consumer) {
        return provider.open(new IntegrationApplication("test-connection", "test-enterprise", "feishu", "test-tenant", "test-app", 1),
            "test-only-secret", consumer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "card"})
    void cardActionReturnsOperatorFeedbackInAcknowledgement(String frameType) throws Exception {
        var response = ChatCardInteractionTransactions.toast("success", "本任务将仅发送链接。");
        try (var connection = provider.openInteractive(new IntegrationApplication("test-connection", "test-enterprise", "feishu",
                "test-tenant", "test-app", 1), "test-only-secret", event -> response)) {
            var incoming = frame(1, 0, event("card.action.trigger")).toBuilder().setHeaders(0, header("type", frameType)).build();
            receive(incoming);
            assertTrue(connection.ready());
            var ack = json.readTree(sent.getFirst().getPayload().toByteArray());
            assertEquals(200, ack.path("code").asInt());
            assertEquals(response, json.readTree(Base64.getDecoder().decode(ack.path("data").asText())));
        }
    }

    private String event(String type) {
        return "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"test-event\",\"event_type\":\"" + type
            + "\",\"app_id\":\"test-app\",\"tenant_key\":\"test-tenant\"},\"event\":{\"synthetic\":true}}";
    }

    private void receive(Pbbp2.Frame frame) {
        listener.get().onBinary(socket, ByteBuffer.wrap(frame.toByteArray()), true);
    }

    private Pbbp2.Frame frame(int sum, int sequence, String payload) {
        return Pbbp2.Frame.newBuilder().setSeqID(93).setLogID(42).setService(87).setMethod(1)
            .addHeaders(header("type", "event")).addHeaders(header("message_id", "test-message"))
            .addHeaders(header("sum", Integer.toString(sum))).addHeaders(header("seq", Integer.toString(sequence)))
            .setPayload(ByteString.copyFromUtf8(payload)).build();
    }

    private Pbbp2.Header header(String name, String value) {
        return Pbbp2.Header.newBuilder().setKey(name).setValue(value).build();
    }
}

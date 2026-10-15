package com.stonewu.agenteam.service.chat.provider.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.ChatConnectionProvider;
import com.stonewu.agenteam.service.chat.ChatSocket;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationFrameDiagnostics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 企业微信官方智能机器人协议；不将自建应用凭据或临时回复地址当作机器人身份。 */
@Component
public class WecomAiBotConnectionProvider implements ChatConnectionProvider {
    private final HttpClient client;
    private final ObjectMapper json;
    private final IntegrationFrameDiagnostics diagnostics;
    private final Clock clock;

    public WecomAiBotConnectionProvider(@Qualifier("chatHttpClient") HttpClient client, ObjectMapper json,
            IntegrationFrameDiagnostics diagnostics, Clock clock) {
        this.client = client;
        this.json = json;
        this.diagnostics = diagnostics;
        this.clock = clock;
    }

    @Override
    public String providerCode() {
        return "wecom_aibot";
    }

    @Override
    public Connection open(IntegrationApplication application, String secret, Consumer<JsonNode> events) {
        var live = new Live(application.id(), events);
        try {
            live.socket.connect(client, URI.create("wss://openws.work.weixin.qq.com"));
            var body = json.createObjectNode().put("bot_id", application.externalAppId()).put("secret", secret);
            var result = live.request("aibot_subscribe_" + UUID.randomUUID(), "aibot_subscribe", body, () -> { });
            if (!result.path("errcode").isIntegralNumber() || result.path("errcode").asLong() != 0) {
                throw new ChannelProviderException("CHAT_AUTHENTICATION_FAILED", "企微智能机器人认证未通过，请核对专用编号和密钥。");
            }
            live.authenticated = true;
            return live;
        } catch (RuntimeException failure) {
            live.close();
            throw failure;
        }
    }

    private final class Live implements Connection, ChatSocket.Receiver {
        private final String connectionId;
        private final Consumer<JsonNode> events;
        private final ChatSocket socket;
        private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private volatile boolean authenticated;
        private volatile boolean displaced;
        private volatile Instant lastPong = clock.instant();
        private volatile Instant nextPing = clock.instant().plusSeconds(30);

        private Live(String connectionId, Consumer<JsonNode> events) {
            this.connectionId = connectionId;
            this.events = events;
            this.socket = new ChatSocket(connectionId, this);
        }

        @Override
        public void text(String value) {
            try {
                var frame = json.readTree(value);
                String command = frame.path("cmd").asText();
                if (command.equals("aibot_msg_callback") || command.equals("aibot_event_callback")) {
                    if (!authenticated) {
                        throw new ChannelProviderException("CHAT_AUTHENTICATION_REQUIRED", "企微在认证完成前推送了业务事件。");
                    }
                    if (frame.path("body").path("event").path("eventtype").asText().equals("disconnected_event")) {
                        displaced = true;
                        try {
                            events.accept(frame);
                        } finally {
                            close();
                        }
                        return;
                    }
                    events.accept(frame);
                    return;
                }
                String id = frame.path("headers").path("req_id").asText();
                var waiting = pending.remove(id);
                if (waiting != null) {
                    if (id.startsWith("aibot_subscribe_") && frame.path("errcode").isIntegralNumber() && frame.path("errcode").asLong() == 0) {
                        authenticated = true;
                    }
                    waiting.complete(frame);
                }
            } catch (Exception failure) {
                throw ChatSocket.safe("CHAT_FRAME_INVALID", "企微长连接帧处理失败。", failure);
            }
        }

        @Override
        public void binary(byte[] value) {
            throw new IllegalArgumentException("企微长连接返回了非文本帧");
        }

        @Override
        public JsonNode request(String id, String command, JsonNode body, Runnable beforeSend) {
            if (pending.size() >= 64 || id == null || !id.matches("[A-Za-z0-9_-]{1,191}")) {
                throw new ChannelProviderException("CHAT_SEND_BUSY", "机器人有较多未完成的发送，请稍后重试。");
            }
            var future = new CompletableFuture<JsonNode>();
            if (pending.putIfAbsent(id, future) != null) {
                throw new ChannelProviderException("CHAT_SEND_BUSY", "此发送请求正在处理。");
            }
            var request = json.createObjectNode().put("cmd", command);
            request.putObject("headers").put("req_id", id);
            if (body != null) {
                request.set("body", body);
            }
            try {
                beforeSend.run();
                socket.text(request.toString());
                var result = future.get(5, TimeUnit.SECONDS);
                diagnostics.response(connectionId, command, request, result);
                return result;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw ChatSocket.safe("CHAT_SEND_UNKNOWN", "企微尚未确认发送结果。", failure);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw ChatSocket.safe("CHAT_SEND_UNKNOWN", "企微尚未确认发送结果。", failure);
            } finally {
                pending.remove(id, future);
            }
        }

        @Override
        public void tick(Instant now) {
            if (!nextPing.isAfter(now)) {
                var result = request("ping_" + UUID.randomUUID(), "ping", null, () -> { });
                if (result.path("errcode").isIntegralNumber() && result.path("errcode").asLong() == 0) {
                    lastPong = now;
                }
                nextPing = now.plusSeconds(30);
            }
            if (lastPong.plusSeconds(70).isBefore(now)) {
                close();
            }
        }

        @Override
        public boolean ready() {
            return authenticated && !displaced && socket.open();
        }

        @Override
        public boolean replaced() {
            return displaced;
        }

        @Override
        public void closed() {
            authenticated = false;
            pending.values().forEach(value -> value.completeExceptionally(new ChannelProviderException("CHAT_CONNECTION_CLOSED", "企微长连接已断开。")));
            pending.clear();
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}

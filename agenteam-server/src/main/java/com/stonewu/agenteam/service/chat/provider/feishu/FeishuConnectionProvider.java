package com.stonewu.agenteam.service.chat.provider.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.event.CustomEventHandler;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.pb.Pbbp2;
import com.stonewu.agenteam.configuration.integration.IntegrationEndpoints;
import com.stonewu.agenteam.mapper.integration.ChannelResponseMapper;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.ChatConnectionProvider;
import com.stonewu.agenteam.service.chat.ChatSocket;
import com.stonewu.agenteam.service.integration.IntegrationHttpClient;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import com.stonewu.agenteam.service.chat.ChatCardInteractionService;

/** 官方帧和事件分派复用开发包，连接生命周期由群策管理。 */
@Component
public class FeishuConnectionProvider implements ChatConnectionProvider {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuConnectionProvider.class);
    private final IntegrationHttpClient http;
    private final IntegrationEndpoints endpoints;
    private final ChannelResponseMapper responses;
    private final ObjectMapper json;
    private final HttpClient client;
    private final Clock clock;

    public FeishuConnectionProvider(IntegrationHttpClient http, IntegrationEndpoints endpoints, ChannelResponseMapper responses,
            ObjectMapper json, @Qualifier("chatHttpClient") HttpClient client, Clock clock) {
        this.http = http;
        this.endpoints = endpoints;
        this.responses = responses;
        this.json = json;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public String providerCode() {
        return "feishu";
    }

    @Override
    public Connection open(IntegrationApplication application, String secret, Consumer<JsonNode> events) {
        return openInteractive(application, secret, event -> {
            events.accept(event);
            return null;
        });
    }

    @Override
    public Connection openInteractive(IntegrationApplication application, String secret, Function<JsonNode, JsonNode> events) {
        var body = json.createObjectNode().put("AppID", application.externalAppId()).put("AppSecret", secret);
        var data = responses.requireSuccess(http.post(application.id(), endpoints.feishu("/callback/ws/endpoint"), Map.of(), body, null), true).path("data");
        URI address = address(data.path("URL").asText());
        int service = serviceId(address);
        var connection = new Live(application.id(), events, service, data.path("ClientConfig").path("PingInterval").asInt(120));
        connection.socket.connect(client, address);
        return connection;
    }

    public static URI address(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"wss".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443
                || !(host.endsWith(".feishu.cn") || host.endsWith(".larksuite.com"))) {
                throw new IllegalArgumentException("飞书未返回允许的长连接地址");
            }
            return uri;
        } catch (RuntimeException failure) {
            throw ChatSocket.safe("CHAT_ENDPOINT_INVALID", "平台未返回有效的长连接地址。", failure);
        }
    }

    private int serviceId(URI uri) {
        for (String part : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
            String[] field = part.split("=", 2);
            if (field.length == 2 && field[0].equals("service_id")) {
                String value = URLDecoder.decode(field[1], StandardCharsets.UTF_8);
                if (value.matches("[0-9]{1,9}")) {
                    return Integer.parseInt(value);
                }
            }
        }
        throw new ChannelProviderException("CHAT_ENDPOINT_INVALID", "平台未返回长连接服务编号。");
    }

    private final class Live implements Connection, ChatSocket.Receiver {
        private final String connectionId;
        private final Function<JsonNode, JsonNode> events;
        private final int service;
        private final ChatSocket socket;
        private final FeishuFrameCodec codec = new FeishuFrameCodec();
        private volatile int pingSeconds;
        private volatile Instant nextPing = Instant.MIN;
        private volatile Instant lastPong = clock.instant();

        private Live(String connectionId, Function<JsonNode, JsonNode> events, int service, int pingSeconds) {
            this.connectionId = connectionId;
            this.events = events;
            this.service = service;
            this.pingSeconds = Math.max(10, Math.min(300, pingSeconds));
            socket = new ChatSocket(connectionId, this);
        }

        @Override
        public void text(String value) {
            throw new IllegalArgumentException("飞书长连接返回了非二进制帧");
        }

        @Override
        public void binary(byte[] bytes) {
            try {
                var frame = Pbbp2.Frame.parseFrom(bytes);
                String type = FeishuFrameCodec.header(frame, "type");
                if (frame.getMethod() == 0) {
                    if (type.equals("pong")) {
                        lastPong = clock.instant();
                        if (frame.hasPayload() && !frame.getPayload().isEmpty()) {
                            pingSeconds = Math.max(10, Math.min(300, json.readTree(frame.getPayload().toByteArray()).path("PingInterval").asInt(pingSeconds)));
                        }
                    }
                    return;
                }
                if (frame.getMethod() != 1 || !(type.equals("event") || type.equals("card"))) {
                    return;
                }
                byte[] payload = codec.payload(frame, clock.instant());
                if (payload == null) {
                    return;
                }
                long started = System.nanoTime();
                int code = 200;
                byte[] response = null;
                try {
                    var event = json.readTree(payload);
                    String eventType = event.path("header").path("event_type").asText();
                    if (eventType.isBlank() || eventType.length() > 191) {
                        throw new IllegalArgumentException("飞书事件类型无效");
                    }
                    if (ChatCardInteractionService.isCard(event)) {
                        var result = events.apply(event);
                        response = result == null ? null : json.writeValueAsBytes(result);
                    } else {
                        new EventDispatcher.Builder("", "").onCustomizedEvent(eventType, new CustomEventHandler() {
                            @Override
                            public void handle(EventReq request) {
                                events.apply(event);
                            }
                        }).build().doWithoutValidation(payload);
                    }
                } catch (Throwable failure) {
                    code = 500;
                    LOG.warn("飞书事件未完成持久保存，接入={}", connectionId,
                        ChatSocket.safe("CHAT_EVENT_RETRY", "等待平台重新投递消息。", failure));
                }
                socket.binary(FeishuFrameCodec.acknowledge(frame, code, (System.nanoTime() - started) / 1_000_000, response));
            } catch (Exception failure) {
                throw ChatSocket.safe("CHAT_FRAME_INVALID", "飞书长连接帧处理失败。", failure);
            }
        }

        @Override
        public void tick(Instant now) {
            if (lastPong.plusSeconds(pingSeconds * 2L + 10).isBefore(now)) {
                close();
            } else if (!nextPing.isAfter(now)) {
                socket.binary(FeishuFrameCodec.ping(service));
                nextPing = now.plusSeconds(pingSeconds);
            }
        }

        @Override
        public boolean ready() {
            return socket.open();
        }

        @Override
        public void closed() {
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}

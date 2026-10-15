package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationFrameDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** 单条连接有界接收，不记录连接票据、正文或平台关闭原因。 */
public final class ChatSocket implements WebSocket.Listener, AutoCloseable {
    public interface Receiver {
        void text(String value);
        void binary(byte[] value);
        void closed();
    }

    private static final Logger LOG = LoggerFactory.getLogger(ChatSocket.class);
    private static final int LIMIT = 1_048_576;
    private final String connectionId;
    private final Receiver receiver;
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private volatile WebSocket socket;
    private volatile boolean closed;

    public ChatSocket(String connectionId, Receiver receiver) {
        this.connectionId = connectionId;
        this.receiver = receiver;
    }

    public void connect(HttpClient client, URI address) {
        try {
            var connected = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
                .buildAsync(address, this).get(10, TimeUnit.SECONDS);
            socket = connected;
            if (closed) {
                connected.abort();
            }
        } catch (Exception failure) {
            IntegrationFrameDiagnostics.networkFailure(connectionId, "建立连接", failure);
            close();
            throw safe("CHAT_CONNECT_FAILED", "平台长连接暂时无法建立。", failure);
        }
    }

    @Override
    public void onOpen(WebSocket value) {
        socket = value;
        value.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket value, CharSequence part, boolean last) {
        try {
            if (text.length() + part.length() > LIMIT) {
                throw new IllegalArgumentException("平台文本帧超过允许大小");
            }
            text.append(part);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                receiver.text(message);
            }
            value.request(1);
        } catch (RuntimeException failure) {
            failed(failure);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket value, ByteBuffer part, boolean last) {
        try {
            if (binary.size() + part.remaining() > LIMIT) {
                throw new IllegalArgumentException("平台二进制帧超过允许大小");
            }
            byte[] bytes = new byte[part.remaining()];
            part.get(bytes);
            binary.writeBytes(bytes);
            if (last) {
                byte[] message = binary.toByteArray();
                binary.reset();
                receiver.binary(message);
            }
            value.request(1);
        } catch (RuntimeException failure) {
            failed(failure);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket value, int status, String reason) {
        close();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket value, Throwable error) {
        IntegrationFrameDiagnostics.networkFailure(connectionId, "读取连接", error);
        failed(error);
    }

    public synchronized void text(String payload) {
        try {
            requireOpen().sendText(payload, true).get(8, TimeUnit.SECONDS);
        } catch (Exception failure) {
            IntegrationFrameDiagnostics.networkFailure(connectionId, "发送文字", failure);
            throw safe("CHAT_SOCKET_SEND_UNCERTAIN", "平台长连接发送结果尚未确认。", failure);
        }
    }

    public synchronized void binary(byte[] payload) {
        try {
            requireOpen().sendBinary(ByteBuffer.wrap(payload), true).get(8, TimeUnit.SECONDS);
        } catch (Exception failure) {
            IntegrationFrameDiagnostics.networkFailure(connectionId, "发送二进制内容", failure);
            throw safe("CHAT_SOCKET_SEND_UNCERTAIN", "平台长连接发送结果尚未确认。", failure);
        }
    }

    public boolean open() {
        var value = socket;
        return !closed && value != null && !value.isInputClosed() && !value.isOutputClosed();
    }

    private WebSocket requireOpen() {
        if (!open()) {
            throw new ChannelProviderException("CHAT_CONNECTION_UNAVAILABLE", "机器人长连接尚未就绪。");
        }
        return socket;
    }

    private void failed(Throwable failure) {
        LOG.warn("平台长连接处理失败，接入={}", connectionId, safe("CHAT_SOCKET_FAILED", "平台长连接已中止。", failure));
        close();
    }

    public static ChannelProviderException safe(String code, String message, Throwable failure) {
        return new ChannelProviderException(code, message, failure);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            var value = socket;
            if (value != null) {
                value.abort();
            }
            receiver.closed();
        }
    }
}

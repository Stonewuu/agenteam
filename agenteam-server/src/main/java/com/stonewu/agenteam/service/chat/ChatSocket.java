package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

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
            IntegrationFrameDiagnostics.networkFailure(connectionId, Messages.text("text.chat.chatSocket.connect.1"), failure);
            close();
            throw safe("CHAT_CONNECT_FAILED", Messages.text("error.chat.chatSocket.connect.1"), failure);
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
                throw new IllegalArgumentException(Messages.text("error.chat.chatSocket.onText.1"));
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
                throw new IllegalArgumentException(Messages.text("error.chat.chatSocket.onBinary.1"));
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
        IntegrationFrameDiagnostics.networkFailure(connectionId, Messages.text("text.chat.chatSocket.onError.1"), error);
        failed(error);
    }

    public synchronized void text(String payload) {
        try {
            requireOpen().sendText(payload, true).get(8, TimeUnit.SECONDS);
        } catch (Exception failure) {
            IntegrationFrameDiagnostics.networkFailure(connectionId, Messages.text("text.chat.chatSocket.text.1"), failure);
            throw safe("CHAT_SOCKET_SEND_UNCERTAIN", Messages.text("error.chat.chatSocket.text.1"), failure);
        }
    }

    public synchronized void binary(byte[] payload) {
        try {
            requireOpen().sendBinary(ByteBuffer.wrap(payload), true).get(8, TimeUnit.SECONDS);
        } catch (Exception failure) {
            IntegrationFrameDiagnostics.networkFailure(connectionId, Messages.text("text.chat.chatSocket.binary.1"), failure);
            throw safe("CHAT_SOCKET_SEND_UNCERTAIN", Messages.text("error.chat.chatSocket.text.1"), failure);
        }
    }

    public boolean open() {
        var value = socket;
        return !closed && value != null && !value.isInputClosed() && !value.isOutputClosed();
    }

    private WebSocket requireOpen() {
        if (!open()) {
            throw new ChannelProviderException("CHAT_CONNECTION_UNAVAILABLE", Messages.text("error.chat.chatSocket.requireOpen.1"));
        }
        return socket;
    }

    private void failed(Throwable failure) {
        LOG.warn(Messages.log("log.chat.chatSocket.failed.1"), connectionId, safe("CHAT_SOCKET_FAILED", Messages.text("error.chat.chatSocket.failed.1"), failure));
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

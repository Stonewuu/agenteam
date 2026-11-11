package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested.Stage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** 数据库负责可靠保存，进程通知只让已经提交的工作尽快开始。 */
@Service
public class ChatWorkCoordinator implements MessageListener, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatWorkCoordinator.class);
    private final ChatConfiguration configuration;
    private final Map<Stage, ChatWorkRunner> workers = new EnumMap<>(Stage.class);
    private final ChatWorkRunner broadcasts;
    private final String instance = UUID.randomUUID().toString();
    private final String topic;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ChatWorkCoordinator(ChatConfiguration configuration, ObjectProvider<ChatNormalizationWorker> incoming,
            ObjectProvider<ChatRoutingWorker> routing, ObjectProvider<ChatSendWorker> sending, StringRedisTemplate redis,
            @Value("${spring.session.redis.namespace:agenteam}") String namespace) {
        this.configuration = configuration;
        this.topic = namespace + ":chat-send-ready";
        workers.put(Stage.RECEIVE, new ChatWorkRunner("receive", () -> batch(() -> incoming.getObject().runOnce())));
        workers.put(Stage.ROUTE, new ChatWorkRunner("route", () -> {
            if (configuration.isWorkerEnabled()) {
                routing.getObject().processPending();
            }
            return false;
        }));
        workers.put(Stage.SEND, new ChatWorkRunner("send", () -> batch(() -> sending.getObject().runOnce())));
        broadcasts = new ChatWorkRunner("signal", () -> {
            if (configuration.isWorkerEnabled()) {
                try {
                    redis.convertAndSend(topic, instance);
                } catch (RuntimeException unavailable) {
                    LOG.debug(Messages.log("log.chat.chatWorkCoordinator.init.1"));
                }
            }
            return false;
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void committed(ChatWorkRequested event) {
        if (closed.get() || !configuration.isWorkerEnabled()) {
            return;
        }
        wake(event.stage());
        if (event.stage() == Stage.SEND) {
            broadcasts.wake();
        }
    }

    public void wake(Stage stage) {
        if (!closed.get() && configuration.isWorkerEnabled()) {
            workers.get(stage).wake();
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (closed.get() || !configuration.isWorkerEnabled() || message.getBody().length > 64) {
            return;
        }
        String origin = new String(message.getBody(), StandardCharsets.UTF_8);
        if (!instance.equals(origin) && origin.matches("[a-f0-9-]{36}")) {
            wake(Stage.SEND);
        }
    }

    public String topic() {
        return topic;
    }

    private boolean batch(BooleanSupplier work) {
        int size = Math.max(1, Math.min(50, configuration.getBatchSize()));
        for (int index = 0; index < size; index++) {
            if (closed.get() || !configuration.isWorkerEnabled() || !work.getAsBoolean()) {
                return false;
            }
        }
        return true;
    }

    @PreDestroy
    @Override
    public void close() {
        closed.set(true);
        workers.values().forEach(ChatWorkRunner::close);
        broadcasts.close();
    }
}

package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested;
import com.stonewu.agenteam.model.chat.entity.ChatWorkRequested.Stage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatWorkCoordinatorTest {
    @Test
    void unavailableRedisDoesNotBlockLocalWorkAndDisabledWorkersIgnoreSignals() throws Exception {
        var incoming = mock(ChatNormalizationWorker.class);
        var routing = mock(ChatRoutingWorker.class);
        var sending = mock(ChatSendWorker.class);
        var redis = mock(StringRedisTemplate.class);
        var config = new ChatConfiguration();
        config.setWorkerEnabled(true);
        var local = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        when(sending.runOnce()).thenAnswer(call -> {
            local.countDown();
            return false;
        });
        doAnswer(call -> {
            attempted.countDown();
            throw new IllegalStateException("合成Redis连接中断");
        }).when(redis).convertAndSend(anyString(), any());
        try (var coordinator = create(config, incoming, routing, sending, redis, "test")) {
            coordinator.committed(new ChatWorkRequested(Stage.SEND));
            assertTrue(local.await(5, TimeUnit.SECONDS));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            config.setWorkerEnabled(false);
            coordinator.onMessage(message(UUID.randomUUID().toString()), null);
            coordinator.committed(new ChatWorkRequested(Stage.RECEIVE));
            verifyNoInteractions(incoming, routing);
        }
    }

    @Test
    void remoteNotificationWakesOnlySendingAndNeverRebroadcasts() throws Exception {
        var incoming = mock(ChatNormalizationWorker.class);
        var routing = mock(ChatRoutingWorker.class);
        var sending = mock(ChatSendWorker.class);
        var redis = mock(StringRedisTemplate.class);
        var config = new ChatConfiguration();
        config.setWorkerEnabled(true);
        var woken = new CountDownLatch(1);
        when(sending.runOnce()).thenAnswer(call -> {
            woken.countDown();
            return false;
        });
        try (var coordinator = create(config, incoming, routing, sending, redis, "test")) {
            coordinator.onMessage(message("任意正文不能作为工作通知"), null);
            coordinator.onMessage(message("a".repeat(100)), null);
            verifyNoInteractions(sending);
            coordinator.onMessage(message(UUID.randomUUID().toString()), null);
            assertTrue(woken.await(5, TimeUnit.SECONDS));
            verifyNoInteractions(incoming, routing, redis);
        }
    }

    static ChatWorkCoordinator create(ChatConfiguration config, ChatNormalizationWorker incoming, ChatRoutingWorker routing,
            ChatSendWorker sending, StringRedisTemplate redis, String namespace) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("incoming", incoming);
        factory.registerSingleton("routing", routing);
        factory.registerSingleton("sending", sending);
        return new ChatWorkCoordinator(config, factory.getBeanProvider(ChatNormalizationWorker.class),
            factory.getBeanProvider(ChatRoutingWorker.class), factory.getBeanProvider(ChatSendWorker.class), redis, namespace);
    }

    private static Message message(String value) {
        var message = mock(Message.class);
        when(message.getBody()).thenReturn(value.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}

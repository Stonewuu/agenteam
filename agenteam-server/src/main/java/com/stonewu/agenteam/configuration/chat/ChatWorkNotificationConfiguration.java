package com.stonewu.agenteam.configuration.chat;

import com.stonewu.agenteam.service.chat.ChatWorkCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 发送通知只包含进程编号，不转发消息正文、答案或平台凭据。 */
@Configuration
@ConditionalOnProperty(name = "chat.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ChatWorkNotificationConfiguration {
    @Bean(name = "chatWorkNotificationExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofVirtual().name("chat-notification-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public RedisMessageListenerContainer chatWorkNotifications(RedisConnectionFactory factory, ChatWorkCoordinator coordinator,
            @Qualifier("chatWorkNotificationExecutor") ThreadPoolExecutor executor) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setTaskExecutor(executor);
        container.setRecoveryInterval(5000);
        container.addMessageListener(coordinator, new ChannelTopic(coordinator.topic()));
        return container;
    }
}

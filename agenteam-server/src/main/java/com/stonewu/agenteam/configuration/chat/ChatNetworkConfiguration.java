package com.stonewu.agenteam.configuration.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.net.http.HttpClient;
import java.time.Duration;

/** 共享网络客户端，不为每个机器人建立无法释放的线程池。 */
@Configuration
public class ChatNetworkConfiguration {
    @Bean(name = "chatHttpClient", destroyMethod = "close")
    public HttpClient chatHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean(name = "chatConnectionScheduler", defaultCandidate = false)
    public ThreadPoolTaskScheduler connections() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-lease-");
        return scheduler;
    }

    @Bean(name = "chatBackgroundScheduler", defaultCandidate = false)
    public ThreadPoolTaskScheduler background() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("chat-work-");
        return scheduler;
    }
}

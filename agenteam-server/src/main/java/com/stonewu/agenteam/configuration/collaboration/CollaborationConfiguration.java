package com.stonewu.agenteam.configuration.collaboration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 岗位协作的服务端限制，任务创建后保存实际使用的值。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "collaboration")
public class CollaborationConfiguration {
    @Bean(name = "collaborationScheduler", defaultCandidate = false)
    public ThreadPoolTaskScheduler collaborationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("collaboration-work-");
        return scheduler;
    }

    private boolean webWorkerEnabled = true;
    private int batchSize = 50;
    private int maxWorks = 20;
    private int maxHandoffs = 8;
    private int maxRuns = 40;
    private int maxParallel = 2;
    private int deadlineHours = 24;
    private int maxContextCharacters = 24000;
    private int maxContextMessages = 30;
}

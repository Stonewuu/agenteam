package com.stonewu.agenteam.configuration.execution;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 事件保存线程数量受限，不随事件连接或模型片段增加线程。
 */
@Configuration
@EnableScheduling
public class ExecutionWorkerConfiguration {

    @Bean(destroyMethod = "dispose")
    public Scheduler eventPersistenceScheduler() {
        return Schedulers.newBoundedElastic(10, 100, "execution-events");
    }
}

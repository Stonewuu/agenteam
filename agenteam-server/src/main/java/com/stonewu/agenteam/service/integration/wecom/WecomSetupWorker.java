package com.stonewu.agenteam.service.integration.wecom;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** 限制同时处理数量，数据库租约保证多个服务不会同时推进同一创建流程。 */
@Component
public class WecomSetupWorker {
    private static final Logger LOG = LoggerFactory.getLogger(WecomSetupWorker.class);
    private final String owner = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Semaphore slots = new Semaphore(2);
    private final WecomSetupStore store;
    private final WecomSetupProcessor processor;
    private final boolean enabled;

    public WecomSetupWorker(WecomSetupStore store, WecomSetupProcessor processor,
            @Value("${agenteam.wecom-setup.worker-enabled:true}") boolean enabled) {
        this.store = store;
        this.processor = processor;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!enabled) {
            return;
        }
        for (var candidate : store.due()) {
            if (!slots.tryAcquire()) {
                return;
            }
            try {
                var claimed = store.claim(candidate, owner);
                if (claimed == null) {
                    slots.release();
                    continue;
                }
                executor.submit(() -> {
                    try {
                        processor.process(claimed);
                    } finally {
                        slots.release();
                    }
                });
            } catch (RuntimeException failure) {
                slots.release();
                LOG.error("企微创建任务无法领取，创建记录={}", candidate.getId(), failure);
            }
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}

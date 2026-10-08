package com.stonewu.agenteam.configuration.notification;

import com.stonewu.agenteam.service.notification.ChannelDeliveryWorker;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 通知使用独立的四个工作线程，不占用智能体执行线程或无限堆积待处理请求。 */
@Configuration
@ConditionalOnProperty(name = {"agenteam.maintenance.worker-enabled", "agenteam.notification.channel-worker-enabled"},
    havingValue = "true", matchIfMissing = true)
public class ChannelDeliveryScheduling {
    private final ChannelDeliveryWorker worker;
    private final Semaphore available = new Semaphore(4);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), Thread.ofPlatform().daemon().name("channel-delivery-", 0).factory());

    public ChannelDeliveryScheduling(ChannelDeliveryWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${agenteam.notification.channel-poll-ms:500}", initialDelay = 1000)
    public void poll() {
        for (int count = 0; count < 4 && available.tryAcquire(); count++) {
            try {
                executor.execute(() -> {
                    try {
                        worker.runOnce();
                    } finally {
                        available.release();
                    }
                });
            } catch (RuntimeException failure) {
                available.release();
                throw failure;
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}

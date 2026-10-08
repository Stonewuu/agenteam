package com.stonewu.agenteam.configuration.schedule;

import com.stonewu.agenteam.service.schedule.ScheduleActionReconciler;
import com.stonewu.agenteam.service.schedule.ScheduleActionStore;
import com.stonewu.agenteam.service.schedule.ScheduleActionWorker;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 两个有界线程准备操作，独立扫描处理已提交操作的最终结果。 */
@Configuration
@ConditionalOnProperty(name = {"agenteam.maintenance.worker-enabled", "agenteam.schedule.action-worker-enabled"}, havingValue = "true", matchIfMissing = true)
public class ScheduleActionScheduling {
    private static final Logger log = LoggerFactory.getLogger(ScheduleActionScheduling.class);
    private final ScheduleActionWorker worker;
    private final ScheduleActionStore store;
    private final ScheduleActionReconciler reconciler;
    private final Semaphore available = new Semaphore(2);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("scheduled-action-", 0).factory());

    public ScheduleActionScheduling(ScheduleActionWorker worker, ScheduleActionStore store, ScheduleActionReconciler reconciler) {
        this.worker = worker;
        this.store = store;
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${agenteam.schedule.action-poll-ms:500}", initialDelay = 1000)
    public void poll() {
        for (int count = 0; count < 2 && available.tryAcquire(); count++) {
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

    @Scheduled(fixedDelayString = "${agenteam.schedule.result-poll-ms:1000}", initialDelay = 1500)
    public void reconcile() {
        for (var row : store.candidates()) {
            try {
                reconciler.reconcile(row);
            } catch (RuntimeException failure) {
                log.warn("定时操作结果暂时无法汇总，发生记录编号={}", row.getId(), failure);
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}

package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.model.background.entity.JobLease;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 操作准备使用独立租约；真正的智能体执行和渠道网络分别由已有工作进程处理。 */
@Service
public class ScheduleActionWorker {
    private static final Logger log = LoggerFactory.getLogger(ScheduleActionWorker.class);
    private final ScheduleActionTransactions transactions;
    private final String worker = "scheduled-action-" + UUID.randomUUID();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("scheduled-action-lease-", 0).factory());

    public ScheduleActionWorker(ScheduleActionTransactions transactions) {
        this.transactions = transactions;
    }

    public boolean runOnce() {
        JobLease lease;
        try {
            var claimed = transactions.claim(worker);
            if (claimed.isEmpty()) {
                return false;
            }
            lease = claimed.get();
        } catch (RuntimeException failure) {
            log.warn("定时操作准备工作暂时无法领取", failure);
            return false;
        }
        var renewal = heartbeat.scheduleAtFixedRate(() -> renew(lease), 15, 15, TimeUnit.SECONDS);
        try {
            transactions.submit(lease);
        } catch (ScheduleActionStore.LeaseLostException failure) {
            log.warn("定时操作准备结果因租约变化未提交，工作编号={}", lease.id(), failure);
        } catch (RuntimeException failure) {
            log.warn("定时操作准备未完成，工作编号={}", lease.id(), failure);
            try {
                transactions.failure(lease, failure);
            } catch (RuntimeException saveFailure) {
                log.warn("定时操作失败状态暂未保存，工作编号={}", lease.id(), saveFailure);
            }
        } finally {
            renewal.cancel(false);
        }
        return true;
    }

    private void renew(JobLease lease) {
        try {
            transactions.renew(lease);
        } catch (RuntimeException failure) {
            log.warn("定时操作工作租约无法续期，工作编号={}", lease.id(), failure);
        }
    }

    @PreDestroy
    public void close() {
        heartbeat.shutdownNow();
    }
}

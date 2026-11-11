package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.service.i18n.Messages;

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
            log.warn(Messages.log("log.schedule.scheduleActionWorker.runOnce.1"), failure);
            return false;
        }
        var renewal = heartbeat.scheduleAtFixedRate(() -> renew(lease), 15, 15, TimeUnit.SECONDS);
        try {
            transactions.submit(lease);
        } catch (ScheduleActionStore.LeaseLostException failure) {
            log.warn(Messages.log("log.schedule.scheduleActionWorker.runOnce.2"), lease.id(), failure);
        } catch (RuntimeException failure) {
            log.warn(Messages.log("log.schedule.scheduleActionWorker.runOnce.3"), lease.id(), failure);
            try {
                transactions.failure(lease, failure);
            } catch (RuntimeException saveFailure) {
                log.warn(Messages.log("log.schedule.scheduleActionWorker.runOnce.4"), lease.id(), saveFailure);
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
            log.warn(Messages.log("log.schedule.scheduleActionWorker.renew.1"), lease.id(), failure);
        }
    }

    @PreDestroy
    public void close() {
        heartbeat.shutdownNow();
    }
}

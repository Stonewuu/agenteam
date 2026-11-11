package com.stonewu.agenteam.service.modelusage;

import com.stonewu.agenteam.service.i18n.Messages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 复用平台定时线程，每批最多一百条，不随用户数创建后台线程。
 */
@Component
public class ModelUsageRecoveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ModelUsageRecoveryWorker.class);
    private final ModelUsageRecoveryService recovery;
    private final ModelUsageProtocolRepairService repairs;

    public ModelUsageRecoveryWorker(ModelUsageRecoveryService recovery, ModelUsageProtocolRepairService repairs) {
        this.recovery = recovery;
        this.repairs = repairs;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void run() {
        try {
            recovery.recoverInterrupted();
            for (var candidate : repairs.candidates()) {
                try {
                    repairs.repair(candidate);
                } catch (RuntimeException failure) {
                    LOG.warn(Messages.log("log.modelusage.modelUsageRecoveryWorker.run.1"), candidate.enterprise(), candidate.id(), failure);
                    repairs.postpone(candidate);
                }
            }
            for (var work : recovery.claimCalculations()) {
                try {
                    recovery.calculate(work);
                } catch (RuntimeException failure) {
                    LOG.warn(Messages.log("log.modelusage.modelUsageRecoveryWorker.run.2"), work.enterprise(), work.id(), failure);
                    recovery.postpone(work);
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn(Messages.log("log.modelusage.modelUsageRecoveryWorker.run.3"), failure);
        }
    }
}

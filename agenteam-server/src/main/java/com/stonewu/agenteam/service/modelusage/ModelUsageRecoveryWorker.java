package com.stonewu.agenteam.service.modelusage;

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
                    LOG.warn("修正旧模型用量失败，企业={}，调用={}", candidate.enterprise(), candidate.id(), failure);
                    repairs.postpone(candidate);
                }
            }
            for (var work : recovery.claimCalculations()) {
                try {
                    recovery.calculate(work);
                } catch (RuntimeException failure) {
                    LOG.warn("计算模型金额失败，企业={}，调用={}", work.enterprise(), work.id(), failure);
                    recovery.postpone(work);
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("模型用量恢复任务失败，将在下一批继续", failure);
        }
    }
}

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

    public ModelUsageRecoveryWorker(ModelUsageRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void run() {
        try {
            recovery.recoverInterrupted();
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

package com.stonewu.agenteam.configuration.integration;

import com.stonewu.agenteam.service.integration.IntegrationRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 渠道清理按小批次执行，活动发送及其请求内容不会提前清除。 */
@Configuration
@ConditionalOnProperty(name = "agenteam.maintenance.worker-enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationRetentionScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(IntegrationRetentionScheduling.class);
    private final IntegrationRetentionService retention;

    public IntegrationRetentionScheduling(IntegrationRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${agenteam.integration.retention.sweep-ms:60000}", initialDelay = 30000)
    public void sweep() {
        try {
            retention.sweep();
        } catch (RuntimeException failure) {
            LOG.warn("渠道临时资料与过期发送记录清理未完成，任务 integration-retention", failure);
        }
    }
}

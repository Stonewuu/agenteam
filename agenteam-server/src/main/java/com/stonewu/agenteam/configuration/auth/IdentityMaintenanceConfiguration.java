package com.stonewu.agenteam.configuration.auth;

import com.stonewu.agenteam.service.auth.IdentityMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 身份辅助数据每分钟分批整理，不向用户界面展示内部清理状态。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agenteam.maintenance.worker-enabled", havingValue = "true", matchIfMissing = true)
public class IdentityMaintenanceConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityMaintenanceConfiguration.class);

    private final IdentityMaintenanceService service;

    public IdentityMaintenanceConfiguration(IdentityMaintenanceService service) {
        this.service = service;
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void removeExpiredRecords() {
        try {
            for (String enterpriseId : service.enterprisesWithExpiredInvitations()) {
                service.expireInvitations(enterpriseId);
            }
            service.removeExpiredRecords();
        } catch (RuntimeException exception) {
            LOGGER.warn("身份辅助记录暂时无法清理，稍后重试", exception);
        }
    }
}

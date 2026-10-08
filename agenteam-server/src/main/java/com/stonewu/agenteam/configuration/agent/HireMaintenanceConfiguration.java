package com.stonewu.agenteam.configuration.agent;

import com.stonewu.agenteam.service.agent.AgentHireService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 分批结束已到期的申请；新申请与审批也会独立核对实际截止时间。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agenteam.maintenance.worker-enabled", havingValue = "true", matchIfMissing = true)
public class HireMaintenanceConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(HireMaintenanceConfiguration.class);

    private final AgentHireService hires;

    public HireMaintenanceConfiguration(AgentHireService hires) {
        this.hires = hires;
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void expireApplications() {
        try {
            hires.expireApplications();
        } catch (RuntimeException failure) {
            LOGGER.warn("雇佣申请暂时无法整理，稍后重试", failure);
        }
    }
}

package com.stonewu.agenteam.configuration.memory;

import com.stonewu.agenteam.service.memory.MemoryRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 到期正文分批物理删除；执行和页面读取始终另行检查有效期。
 */
@Configuration
@ConditionalOnProperty(name = "agenteam.maintenance.worker-enabled", havingValue = "true", matchIfMissing = true)
public class MemoryMaintenanceConfiguration {

    private final MemoryRetentionService retention;

    public MemoryMaintenanceConfiguration(MemoryRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void clean() {
        retention.clean();
    }
}

package com.stonewu.agenteam.configuration.usage;

import com.stonewu.agenteam.model.operations.entity.QuotaCheckEvent;
import com.stonewu.agenteam.service.usage.QuotaReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 每日核对一次；失败后短暂等待再检查，只有事务提交后才记录修正结果。
 */
@Configuration
@ConditionalOnProperty(name = "agenteam.maintenance.worker-enabled", havingValue = "true", matchIfMissing = true)
public class QuotaMaintenanceScheduling {

    private static final Logger LOG = LoggerFactory.getLogger(QuotaMaintenanceScheduling.class);

    private final QuotaReconciliationService reconciliation;

    private final Clock clock;

    private final ApplicationEventPublisher events;

    private Instant nextCheck = Instant.MIN;

    public QuotaMaintenanceScheduling(QuotaReconciliationService reconciliation, Clock clock,
                                      ApplicationEventPublisher events) {
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.events = events;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void poll() {
        if (clock.instant().isBefore(nextCheck)) {
            return;
        }
        boolean failed = false;
        try {
            for (var enterprise : reconciliation.enterprises()) {
                try {
                    var differences = reconciliation.check(enterprise);
                    for (var value : differences) {
                        LOG.warn("企业 {} 的用量记录 {} 与执行条目不一致，已修正已用次数 {} 为 {}、预留次数 {} 为 {}",
                            enterprise, value.bucketId(), value.savedUsed(), value.actualUsed(), value.savedReserved(),
                            value.actualReserved());
                    }
                    events.publishEvent(new QuotaCheckEvent(differences.size(), true, clock.instant()));
                } catch (RuntimeException unavailable) {
                    failed = true;
                    events.publishEvent(new QuotaCheckEvent(0, false, clock.instant()));
                    LOG.warn("企业 {} 的用量暂时无法核对，稍后重试", enterprise, unavailable);
                }
            }
        } catch (RuntimeException unavailable) {
            failed = true;
            events.publishEvent(new QuotaCheckEvent(0, false, clock.instant()));
            LOG.warn("用量核对暂时无法开始，稍后重试", unavailable);
        }
        nextCheck = clock.instant().plus(failed ? Duration.ofMinutes(1) : Duration.ofDays(1));
    }
}

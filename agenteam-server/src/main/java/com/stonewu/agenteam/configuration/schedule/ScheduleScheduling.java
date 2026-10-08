package com.stonewu.agenteam.configuration.schedule;

import com.stonewu.agenteam.service.schedule.ScheduleTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 到期计划每十秒检查；未来计划定期更新检查时间，较长月份间隔也不会被误判为停机。
 */
@Configuration
@ConditionalOnProperty(name = "agenteam.maintenance.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleScheduling {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleScheduling.class);

    private final ScheduleTriggerService triggers;

    public ScheduleScheduling(ScheduleTriggerService triggers) {
        this.triggers = triggers;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void poll() {
        try {
            for (var candidate : triggers.candidates()) {
                try {
                    triggers.process(candidate);
                } catch (RuntimeException failure) {
                    LOG.warn("计划 {} 本次检查未提交，下一轮继续处理", candidate.id(), failure);
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("计划列表暂时无法读取", failure);
        }
    }
}

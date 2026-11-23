package com.stonewu.agenteam.configuration.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionEventSqlMapper;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 定期清理到期预览和旧版本遗留事件；实时内容由 Redis 的保留期限管理。
 */
@Component
@ConditionalOnProperty(name = "execution.events.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionEventScheduling {
    private final ExecutionEventSqlMapper history;
    private final PreviewRetentionService previews;
    private final Clock clock;

    public ExecutionEventScheduling(ExecutionEventSqlMapper history, PreviewRetentionService previews, Clock clock) {
        this.history = history;
        this.previews = previews;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void clean() {
        previews.clean();
        history.removeExpiredAgentEvent(timestamp(clock.instant().minus(Duration.ofDays(7))));
    }
}

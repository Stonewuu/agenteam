package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * 手动数据读取最多十秒；进程退出后只结束其记录，不能自动重发用户查询。
 */
@Service
@ConditionalOnProperty(name = "data.query-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class ToolReadInterruptionService {
    private final ToolCallMapper calls;
    private final Clock clock;

    public ToolReadInterruptionService(ToolCallMapper calls, Clock clock) {
        this.calls = calls;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${data.query-interruption-interval-ms:30000}")
    public int finishAbandoned() {
        var now = clock.instant();
        return calls.failAbandonedManualReads(now.minus(Duration.ofMinutes(2)), now);
    }
}

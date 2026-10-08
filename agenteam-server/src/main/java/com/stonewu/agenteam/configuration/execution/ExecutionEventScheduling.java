package com.stonewu.agenteam.configuration.execution;

import com.stonewu.agenteam.service.execution.ExecutionEventCompactionWorker;
import com.stonewu.agenteam.service.execution.ExecutionEventPublisher;
import com.stonewu.agenteam.service.execution.PreviewRetentionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 分发失败不影响执行提交，未分发记录会在下次检查时继续处理。
 */
@Component
@ConditionalOnProperty(name = "execution.events.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionEventScheduling {

    private final ExecutionEventPublisher publisher;

    private final PreviewRetentionService previews;

    private final ExecutionEventCompactionWorker compaction;

    public ExecutionEventScheduling(ExecutionEventPublisher publisher, PreviewRetentionService previews,
                                    ExecutionEventCompactionWorker compaction) {
        this.publisher = publisher;
        this.previews = previews;
        this.compaction = compaction;
    }

    @Scheduled(fixedDelayString = "${execution.events.recovery-delay-ms:5000}", initialDelay = 1000)
    public void distribute() {
        publisher.publishPending();
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void clean() {
        previews.clean();
        publisher.removeExpired();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void compactHistory() {
        compaction.compactPending();
    }
}

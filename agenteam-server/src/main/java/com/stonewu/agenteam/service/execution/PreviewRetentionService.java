package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionEventCache;
import com.stonewu.agenteam.mapper.execution.PreviewRetentionMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.service.agent.ExecutionFileCleanup;
import com.stonewu.agenteam.service.file.FileRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * 预览七天到期后清除文件、近期事件和数据库结果，失败后保留记录供下次重试。
 */
@Service
public class PreviewRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(PreviewRetentionService.class);
    private final PreviewRetentionMapper retention;
    private final RunMapper runs;
    private final ExecutionFileCleanup files;
    private final ExecutionEventCache cache;
    private final Clock clock;
    private final FileRetentionService artifacts;

    public PreviewRetentionService(PreviewRetentionMapper retention, RunMapper runs, ExecutionFileCleanup files,
                                   ExecutionEventCache cache, Clock clock, FileRetentionService artifacts) {
        this.retention = retention;
        this.runs = runs;
        this.files = files;
        this.cache = cache;
        this.clock = clock;
        this.artifacts = artifacts;
    }

    public void clean() {
        var before = clock.instant().minus(Duration.ofDays(7));
        for (var preview : retention.expired(before)) {
            try {
                var executions = runs.forConversation(preview.enterprise(), preview.conversation());
                if (executions.stream().anyMatch(run -> !run.terminal())) {
                    continue;
                }
                executions.forEach(run -> {
                    artifacts.removeRunFiles(run);
                    files.remove(run);
                });
                String owner = cache.claim(preview.enterprise(), preview.conversation());
                if (owner == null) {
                    continue;
                }
                try {
                    if (cache.remove(preview.enterprise(), preview.conversation(), owner)) {
                        retention.remove(preview, before);
                    }
                } finally {
                    cache.release(preview.enterprise(), preview.conversation(), owner);
                }
            } catch (RuntimeException failed) {
                LOG.warn("到期预览暂时无法清理，下次继续处理", failed);
            }
        }
    }
}

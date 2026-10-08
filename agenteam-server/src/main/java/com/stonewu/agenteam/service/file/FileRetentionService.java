package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * 到期文件分批清理；数据库不可读取时不能把文件判断为无人引用。
 */
@Service
public class FileRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(FileRetentionService.class);
    private final FileMapper files;
    private final FileRetentionTransactions transactions;
    private final FileContentStorage storage;
    private final Clock clock;
    private final boolean enabled;
    private final DocumentParserProcess parser;

    public FileRetentionService(FileMapper files, FileRetentionTransactions transactions, FileContentStorage storage,
                                Clock clock,
                                @Value("${files.retention.enabled:true}") boolean enabled,
                                DocumentParserProcess parser) {
        this.files = files;
        this.transactions = transactions;
        this.storage = storage;
        this.clock = clock;
        this.enabled = enabled;
        this.parser = parser;
    }

    @Scheduled(fixedDelay = 60000)
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            clean();
        } catch (RuntimeException failure) {
            LOG.warn("文件清理暂未完成，稍后重试", failure);
        }
    }

    public int clean() {
        int removed = 0;
        for (var candidate : files.deletedConversationArtifacts(clock.instant().minus(Duration.ofDays(30)), 100)) {
            var expired = transactions.expireDeletedConversationArtifact(candidate);
            if (expired == null) {
                continue;
            }
            storage.delete(expired.storageKey());
            files.removeDeleted(expired);
            removed++;
        }
        for (var candidate : files.expired(clock.instant(), 100)) {
            var expired = transactions.expire(candidate);
            if (expired == null) {
                continue;
            }
            storage.delete(expired.storageKey());
            files.removeDeleted(expired);
            removed++;
        }
        var before = clock.instant().minus(Duration.ofHours(24));
        return removed + storage.cleanOrphans(before, files::referencesKey, 200) + parser.cleanAbandoned(before, 100);
    }

    public void removeRunFiles(RunRecord run) {
        if (!run.terminal()) {
            throw new IllegalArgumentException("不能清除未结束执行的文件");
        }
        for (var file : files.forRun(run.enterpriseId(), run.id())) {
            if (!file.ownerUserId().equals(run.userId())) {
                throw new IllegalStateException("执行文件与原成员不一致");
            }
            files.deleted(file, clock.instant());
            storage.delete(file.storageKey());
            files.removeDeleted(file);
        }
    }
}

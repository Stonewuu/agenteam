package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectInputFileService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * 先让文件不可访问，再清理内容；普通上传只允许本人删除。
 */
@Service
public class FileRetentionTransactions {
    private final FileMapper files;
    private final FileAccessService access;
    private final Clock clock;
    private final RunMapper runs;
    private final ConversationMapper conversations;
    private final ProjectInputFileService projectInputs;

    public FileRetentionTransactions(FileMapper files, FileAccessService access, Clock clock, RunMapper runs,
                                     ConversationMapper conversations, ProjectInputFileService projectInputs) {
        this.files = files;
        this.access = access;
        this.clock = clock;
        this.runs = runs;
        this.conversations = conversations;
        this.projectInputs = projectInputs;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthContext actor, String id) {
        var file = access.uploadOwner(actor, id, true);
        if (file.expiresAt() == null || projectInputs.contains(file.enterpriseId(), file.ownerUserId(), file.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE", "文件仍被资料或消息使用，请先在对应记录中移除。");
        }
        files.deleted(file, clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FileRecord expire(FileRecord candidate) {
        var file = files.find(candidate.enterpriseId(), candidate.id(), true).orElse(null);
        var now = clock.instant();
        if (file == null || file.expiresAt() == null || file.expiresAt().isAfter(now)
            || (file.uploadLeaseUntil() != null && file.uploadLeaseUntil().isAfter(now))
            || projectInputs.contains(file.enterpriseId(), file.ownerUserId(), file.id())) {
            return null;
        }
        if (file.deletedAt() == null) {
            files.deleted(file, now);
        }
        return files.find(file.enterpriseId(), file.id(), false).orElseThrow();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FileRecord expireDeletedConversationArtifact(FileRecord candidate) {
        if (!candidate.purpose().equals("artifact") || candidate.runId() == null) {
            return null;
        }
        var run = runs.find(candidate.enterpriseId(), candidate.runId(), false)
            .filter(value -> value.userId().equals(candidate.ownerUserId())).orElse(null);
        if (run == null || !run.terminal()) {
            return null;
        }
        var conversation = conversations.find(run.enterpriseId(), run.userId(), run.conversationId(), true)
            .orElse(null);
        if (conversation == null || !conversation.status().equals("deleted") || conversation.activeRunId() != null
            || conversation.deletedAt() == null || conversation.deletedAt()
            .isAfter(clock.instant().minus(Duration.ofDays(30)))) {
            return null;
        }
        var file = files.find(candidate.enterpriseId(), candidate.id(), true).orElse(null);
        if (file == null || file.expiresAt() != null || file.deletedAt() != null || !run.id()
            .equals(file.runId()) || !file.purpose().equals("artifact")) {
            return null;
        }
        files.deleted(file, clock.instant());
        return files.find(file.enterpriseId(), file.id(), false).orElseThrow();
    }
}

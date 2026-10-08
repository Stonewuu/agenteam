package com.stonewu.agenteam.mapper.file;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.file.entity.FileObjectRow;
import com.stonewu.agenteam.model.file.entity.FileQueryRow;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.file.request.FilePrepareRequest;
import com.stonewu.agenteam.model.file.response.FileSummaryView;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 文件状态使用明确条件更新，过期上传不能替换另一次上传的内容。
 */
@Repository
public class FileMapper {
    private final FileSqlMapper statements;

    public FileMapper(FileSqlMapper statements) {
        this.statements = statements;
    }

    public Optional<FileRecord> find(String enterprise, String id, boolean lock) {
        return statements.findFileObject(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public void prepare(String id, String enterprise, String owner, FilePrepareRequest request, String mediaType,
                        Instant now) {
        statements.prepareFileObject(id, enterprise, owner, request.resourceId(), request.purpose(), request.name(),
            mediaType, request.sizeBytes(), request.sha256(), Timestamp.from(now.plusSeconds(900)), "0".repeat(64),
            enterprise + "/" + id + ".data", Timestamp.from(now.plusSeconds(86400)), Timestamp.from(now));
    }

    public void generated(PreparedGeneratedFile file, Instant now) {
        statements.generatedFileObject(file.id(), file.enterpriseId(), file.ownerUserId(), file.resourceId(),
            file.resourceVersionId(), file.runId(), file.purpose(), file.name(), file.mediaType(), file.size(),
            file.sha256(), file.storageKey(), Timestamp.from(now.plusSeconds(86400)), Timestamp.from(now));
    }

    public boolean claimUpload(FileRecord file, String lease, Instant now) {
        return statements.claimUploadFileObject(lease, Timestamp.from(now.plusSeconds(120)), Timestamp.from(now),
            file.enterpriseId(), file.id()) == 1;
    }

    public boolean uploaded(FileRecord file, String lease, String key, long size, String sha256, Instant now) {
        return statements.uploadedFileObject(key, size, sha256, Timestamp.from(now), file.enterpriseId(), file.id(),
            lease) == 1;
    }

    public void releaseUpload(String enterprise, String id, String lease) {
        statements.releaseUploadFileObject(enterprise, id, lease);
    }

    public void scanning(FileRecord file, Instant now) {
        statements.scanningFileObject(Timestamp.from(now), file.enterpriseId(), file.id());
    }

    public boolean inspected(FileRecord file, String status, String errorCode, Instant now) {
        return statements.inspectedFileObject(status, errorCode, Timestamp.from(now), file.enterpriseId(), file.id(),
            file.sha256()) == 1;
    }

    public void deleted(FileRecord file, Instant now) {
        statements.deletedFileObject(Timestamp.from(now), file.enterpriseId(), file.id());
    }

    public void retain(FileRecord file, Instant now) {
        statements.retainFileObject(Timestamp.from(now), file.enterpriseId(), file.id());
    }

    public List<FileRecord> expired(Instant now, int limit) {
        return statements.expiredFileObject(Timestamp.from(now), limit).stream().map(this::map).toList();
    }

    public List<FileRecord> deletedConversationArtifacts(Instant before, int limit) {
        return statements.selectJoinPage(new Page<FileQueryRow>(1, limit, false), FileQueryRow.class,
                JoinWrappers.lambda(FileObjectRow.class)
                    .selectAll(FileObjectRow.class)
                    .innerJoin(AgentRunRow.class, on -> on.eq(AgentRunRow::getEnterpriseId, FileObjectRow::getEnterpriseId)
                        .eq(AgentRunRow::getId, FileObjectRow::getRunId))
                    .innerJoin(AgentConversationRow.class,
                        on -> on.eq(AgentConversationRow::getEnterpriseId, AgentRunRow::getEnterpriseId)
                            .eq(AgentConversationRow::getId, AgentRunRow::getConversationId))
                    .eq(FileObjectRow::getPurpose, "artifact").isNull(FileObjectRow::getExpiresAt)
                    .isNull(FileObjectRow::getDeletedAt)
                    .eq(AgentConversationRow::getStatus, "deleted").le(AgentConversationRow::getDeletedAt, before)
                    .isNull(AgentConversationRow::getActiveRunId)
                    .in(AgentRunRow::getStatus, List.of("completed", "failed", "cancelled"))
                    .orderByAsc(FileObjectRow::getId))
            .getRecords().stream().map(this::map).toList();
    }

    public void removeDeleted(FileRecord file) {
        statements.removeDeletedFileText(file.enterpriseId(), file.id());
        statements.removeDeletedFileDataProfile(file.enterpriseId(), file.id());
        statements.removeDeletedFileDataRow(file.enterpriseId(), file.id());
        statements.removeDeletedFileObject(file.enterpriseId(), file.id());
    }

    public List<FileRecord> forRun(String enterprise, String run) {
        return statements.forRunFileObject(enterprise, run).stream().map(this::map).toList();
    }

    public boolean referencesKey(String key) {
        return DataAccessUtils.nullableSingleResult(statements.referencesKeyFileObject(key)) > 0;
    }

    public static FileSummaryView summary(FileRecord file) {
        return new FileSummaryView(file.id(), file.originalName(), file.mediaType(), file.sizeBytes(), file.status(),
            file.errorCode(), file.createdAt().toString());
    }

    private FileRecord map(FileQueryRow row) {
        return new FileRecord(row.getId(), row.getEnterpriseId(), row.getOwnerUserId(), row.getResourceId(),
            row.getResourceVersionId(), row.getRunId(),
            row.getPurpose(), row.getOriginalName(), row.getMediaType(), row.getExpectedSizeBytes(),
            row.getExpectedSha256(),
            time(row.getUploadExpiresAt()), row.getUploadLeaseId(), time(row.getUploadLeaseUntil()), row.getSizeBytes(),
            row.getSha256(), row.getStorageKey(),
            row.getStatus(), row.getErrorCode(), time(row.getExpiresAt()), time(row.getDeletedAt()),
            time(row.getCreatedAt()));
    }

    private static Instant time(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

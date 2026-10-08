package com.stonewu.agenteam.mapper.knowledge;


import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentQueryRow;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRecord;
import com.stonewu.agenteam.model.knowledge.response.KnowledgeDocumentView;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 资料版本只有完整处理后才切换，失败不改动已有的可读版本。
 */
@Repository
public class KnowledgeDocumentMapper {
    private final KnowledgeDocumentSqlMapper statements;

    private final KnowledgeChunkSqlMapper knowledgeChunkSqlMapper;

    public KnowledgeDocumentMapper(KnowledgeDocumentSqlMapper statements,
                                   KnowledgeChunkSqlMapper knowledgeChunkSqlMapper) {
        this.knowledgeChunkSqlMapper = knowledgeChunkSqlMapper;
        this.statements = statements;
    }

    public Optional<KnowledgeDocumentRecord> find(String enterprise, String id, boolean lock) {
        return statements.findFileObject(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public List<KnowledgeDocumentRecord> list(String enterprise, String resource, String query, PagePosition position,
                                              int limit) {
        return statements.listDocuments(enterprise, resource, query, position, limit + 1).stream().map(this::map)
            .toList();
    }

    public int count(String enterprise, String resource) {
        return DataAccessUtils.nullableSingleResult(statements.countKnowledgeDocument(enterprise, resource));
    }

    public boolean hasDocument(String enterprise, String resource, String file) {
        return DataAccessUtils.nullableSingleResult(
            statements.hasDocumentKnowledgeDocument(enterprise, resource, file)) > 0;
    }

    public boolean referencesReadableFile(String enterprise, String resource, String file) {
        return DataAccessUtils.nullableSingleResult(
            statements.referencesReadableFileKnowledgeDocument(enterprise, resource, file)) > 0;
    }

    public void create(String id, FileRecord file, Instant now) {
        statements.createKnowledgeDocument(id, file.enterpriseId(), file.resourceId(), file.originalName(), file.id(),
            Timestamp.from(now));
    }

    public void reprocess(KnowledgeDocumentRecord doc, Instant now) {
        statements.reprocessKnowledgeDocument(Timestamp.from(now), doc.enterpriseId(), doc.id());
    }

    public void replaceFile(KnowledgeDocumentRecord doc, FileRecord file) {
        statements.replaceFileKnowledgeDocument(file.id(), file.originalName(), doc.enterpriseId(), doc.id());
    }

    public void processing(KnowledgeDocumentRecord doc, Instant now) {
        statements.processingKnowledgeDocument(Timestamp.from(now), doc.enterpriseId(), doc.id());
    }

    public void complete(KnowledgeDocumentRecord doc, int count, Integer pages, Instant now) {
        statements.completeKnowledgeDocument(count, pages, Timestamp.from(now), doc.enterpriseId(), doc.id());
    }

    public void failed(KnowledgeDocumentRecord doc, String code, String summary, boolean retry, Instant now) {
        statements.failedKnowledgeDocument(retry ? "queued" : "failed", retry ? doc.pendingGeneration() : 0, code,
            summary, Timestamp.from(now), doc.enterpriseId(), doc.id());
    }

    public void delete(KnowledgeDocumentRecord doc, Instant now) {
        statements.deleteKnowledgeDocument(Timestamp.from(now), doc.enterpriseId(), doc.id());
        knowledgeChunkSqlMapper.deleteKnowledgeChunk(doc.enterpriseId(), doc.id());
    }

    public static KnowledgeDocumentView view(KnowledgeDocumentRecord doc) {
        return new KnowledgeDocumentView(doc.id(), Long.toString(doc.revision()), doc.createdAt().toString(),
            doc.updatedAt().toString(), doc.name(), doc.fileId(), doc.activeFileId(),
            doc.activeGeneration(), doc.pendingGeneration(), doc.status(), doc.chunkCount(), doc.pageCount(),
            doc.errorCode(), doc.errorSummary(), doc.processedAt() == null ? null : doc.processedAt().toString(),
            doc.sizeBytes());
    }

    private KnowledgeDocumentRecord map(KnowledgeDocumentQueryRow row) {
        return new KnowledgeDocumentRecord(row.getId(), row.getEnterpriseId(), row.getResourceId(), row.getName(),
            row.getFileId(),
            row.getActiveFileId(), row.getActiveGeneration(), row.getPendingGeneration(), row.getLastGeneration(),
            row.getStatus(), row.getChunkCount(),
            row.getPageCount(), row.getErrorCode(), row.getErrorSummary(), time(row.getProcessedAt()),
            time(row.getDeletedAt()), row.getRevision(), time(row.getCreatedAt()), time(row.getUpdatedAt()),
            row.getFileSizeBytes());
    }

    private Instant time(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

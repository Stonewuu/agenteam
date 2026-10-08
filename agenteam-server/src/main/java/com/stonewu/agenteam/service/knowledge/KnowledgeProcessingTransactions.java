package com.stonewu.agenteam.service.knowledge;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeChunkMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.file.entity.DocumentChunk;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRecord;
import com.stonewu.agenteam.service.file.DocumentParserProcess;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * 所有文本块写入都检查当前工作资格，切换前检查实际保存数量。
 */
@Service
public class KnowledgeProcessingTransactions {
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final BackgroundJobMapper jobs;
    private final KnowledgeDocumentService access;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeChunkMapper chunks;
    private final FileMapper files;
    private final Clock clock;

    public KnowledgeProcessingTransactions(AuthMapper users, EnterpriseMapper enterprises, BackgroundJobMapper jobs,
                                           KnowledgeDocumentService access,
                                           KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks,
                                           FileMapper files, Clock clock) {
        this.users = users;
        this.enterprises = enterprises;
        this.jobs = jobs;
        this.access = access;
        this.documents = documents;
        this.chunks = chunks;
        this.files = files;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FileRecord start(JobLease lease, String id, int generation) {
        var doc = owned(lease, id, generation);
        var file = files.find(lease.enterpriseId(), doc.fileId(), true).orElseThrow(FileAccessService::unavailable);
        if (!file.status().equals("ready") || file.deletedAt() != null || !file.resourceId().equals(doc.resourceId())) {
            throw FileAccessService.unavailable();
        }
        chunks.clearPending(doc);
        documents.processing(doc, clock.instant());
        return file;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void append(JobLease lease, String id, int generation, List<DocumentChunk> batch) {
        var doc = owned(lease, id, generation);
        if (batch.isEmpty() || batch.size() > 100) {
            throw new IllegalArgumentException("知识资料写入批次不正确");
        }
        chunks.add(doc, batch, clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(JobLease lease, String id, int generation, int count, Integer pages) {
        var doc = owned(lease, id, generation);
        if (count < 1 || chunks.count(doc) != count) {
            throw DocumentParserProcess.failure("FILE_TYPE_INVALID");
        }
        chunks.removeOldSearchTerms(doc);
        documents.complete(doc, count, pages, clock.instant());
        jobs.finish(lease, "completed", null, null, clock.instant(), clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failed(JobLease lease, String id, int generation, String code, String summary, boolean retry) {
        enterprises.lockEnterprise(lease.enterpriseId());
        if (!jobs.lockOwned(lease, clock.instant())) {
            return;
        }
        var doc = documents.find(lease.enterpriseId(), id, true).orElse(null);
        if (doc == null || doc.deletedAt() != null || doc.pendingGeneration() != generation) {
            jobs.finish(lease, "cancelled", null, null, clock.instant(), clock.instant());
            return;
        }
        chunks.clearPending(doc);
        boolean again = retry && !lease.exhausted() && lease.attemptCount() < lease.maxAttempts();
        documents.failed(doc, code, summary, again, clock.instant());
        jobs.finish(lease, again ? "queued" : "failed", code, summary, clock.instant().plusSeconds(again ? 30 : 0),
            clock.instant());
    }

    private KnowledgeDocumentRecord owned(JobLease lease, String id, int generation) {
        var actor = new AuthContext(
            users.findById(lease.ownerUserId()).orElseThrow(ResourceAuthorizationService::unavailable),
            lease.enterpriseId(), Set.of());
        var doc = documents.find(lease.enterpriseId(), id, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        access.authorize(actor, doc.resourceId(), true);
        if (lease.exhausted() || !jobs.lockOwned(lease, clock.instant())) {
            throw DocumentParserProcess.failure("FILE_PROCESSING_CANCELLED");
        }
        doc = access.require(lease.enterpriseId(), doc.resourceId(), id, true);
        if (doc.pendingGeneration() != generation || generation < 1 || generation <= doc.activeGeneration()) {
            throw DocumentParserProcess.failure("FILE_PROCESSING_CANCELLED");
        }
        return doc;
    }
}

package com.stonewu.agenteam.service.knowledge;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeRetentionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeDocumentRecord;
import com.stonewu.agenteam.model.knowledge.response.KnowledgeDocumentView;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

/**
 * 文档登记、文件保留和处理任务在同一事务提交，业务失败不会留下半批资料。
 */
@Service
public class KnowledgeDocumentService {
    private final ResourcePolicy resources;
    private final FileAccessService access;
    private final FileMapper files;
    private final KnowledgeDocumentMapper documents;
    private final BackgroundJobMapper jobs;
    private final AuditEventService audit;
    private final Clock clock;
    private final KnowledgeRetentionMapper retention;

    public KnowledgeDocumentService(ResourcePolicy resources, FileAccessService access, FileMapper files,
                                    KnowledgeDocumentMapper documents,
                                    BackgroundJobMapper jobs, AuditEventService audit, Clock clock,
                                    KnowledgeRetentionMapper retention) {
        this.resources = resources;
        this.access = access;
        this.files = files;
        this.documents = documents;
        this.jobs = jobs;
        this.audit = audit;
        this.clock = clock;
        this.retention = retention;
    }

    public void authorize(AuthContext actor, String resource, boolean mutation) {
        var record = resources.authorize(actor, resource, mutation ? "edit" : "view", mutation, false);
        if (record.kind() != ResourceKind.KNOWLEDGE) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (mutation) {
            resources.editable(record);
            if (!record.status().equals("active")) {
                throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DISABLED", "知识库已停用，请先恢复使用。");
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<KnowledgeDocumentView> create(AuthContext actor, String resource, List<String> ids) {
        authorize(actor, resource, true);
        if (ids == null || ids.isEmpty() || ids.size() > 10 || new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.invalidField("fileIds", "一次请选择一至十份不同的资料。");
        }
        if (documents.count(actor.enterpriseId(), resource) + ids.size() > 1000) {
            throw new ApiException(HttpStatus.CONFLICT, "KNOWLEDGE_DOCUMENT_LIMIT", "每个知识库最多保存一千份资料。");
        }
        List<KnowledgeDocumentView> result = new ArrayList<>();
        for (String id : ids) {
            var file = access.uploadOwner(actor, id, true);
            if (!file.purpose().equals("knowledge") || !resource.equals(file.resourceId())) {
                throw FileAccessService.unavailable();
            }
            if (!file.status().equals("ready")) {
                throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_READY", "资料尚未通过检查，请等待文件检查完成。");
            }
            if (documents.hasDocument(actor.enterpriseId(), resource, file.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_ADDED",
                    "这份文件已经加入知识库，请使用重新处理。");
            }
            String document = UUID.randomUUID().toString();
            documents.create(document, file, clock.instant());
            files.retain(file, clock.instant());
            enqueue(actor, document, 1);
            result.add(
                KnowledgeDocumentMapper.view(documents.find(actor.enterpriseId(), document, false).orElseThrow()));
        }
        audit.record(actor.enterpriseId(), actor.user(), "knowledge.documents.add", "resource", resource,
            "加入知识资料", Map.of("documentIds", result.stream().map(KnowledgeDocumentView::id).toList()));
        return List.copyOf(result);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public KnowledgeDocumentView reprocess(AuthContext actor, String resource, String id, long revision) {
        authorize(actor, resource, true);
        var doc = require(actor.enterpriseId(), resource, id, true);
        revision(doc, revision);
        requireProcessable(doc);
        var file = files.find(actor.enterpriseId(), doc.fileId(), true).orElseThrow(FileAccessService::unavailable);
        if (!file.status().equals("ready") || file.deletedAt() != null) {
            throw FileAccessService.unavailable();
        }
        documents.reprocess(doc, clock.instant());
        enqueue(actor, id, doc.lastGeneration() + 1);
        audit.record(actor.enterpriseId(), actor.user(), "knowledge.document.reprocess", "knowledge_document", id,
            "重新处理知识资料", Map.of("generation", doc.lastGeneration() + 1));
        return KnowledgeDocumentMapper.view(documents.find(actor.enterpriseId(), id, false).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public KnowledgeDocumentView replaceFile(AuthContext actor, String resource, String id, String fileId,
                                             long revision) {
        authorize(actor, resource, true);
        var doc = require(actor.enterpriseId(), resource, id, true);
        revision(doc, revision);
        requireProcessable(doc);
        var file = access.uploadOwner(actor, fileId, true);
        if (!file.purpose().equals("knowledge") || !resource.equals(file.resourceId())) {
            throw FileAccessService.unavailable();
        }
        if (!file.status().equals("ready")) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_READY", "资料尚未通过检查，请等待文件检查完成。");
        }
        if (documents.hasDocument(actor.enterpriseId(), resource, fileId)) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_ADDED",
                "这份文件已经加入知识库，请选择新的文件。");
        }
        documents.replaceFile(doc, file);
        documents.reprocess(doc, clock.instant());
        files.retain(file, clock.instant());
        retention.releaseUnreferencedFile(actor.enterpriseId(), doc.fileId(), clock.instant());
        enqueue(actor, id, doc.lastGeneration() + 1);
        audit.record(actor.enterpriseId(), actor.user(), "knowledge.document.replace", "knowledge_document", id,
            "更新知识资料文件",
            Map.of("fileId", fileId, "generation", doc.lastGeneration() + 1));
        return KnowledgeDocumentMapper.view(documents.find(actor.enterpriseId(), id, false).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthContext actor, String resource, String id, long revision) {
        authorize(actor, resource, true);
        var doc = require(actor.enterpriseId(), resource, id, true);
        revision(doc, revision);
        documents.delete(doc, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "knowledge.document.delete", "knowledge_document", id,
            "删除知识资料", Map.of("resourceId", resource));
    }

    public KnowledgeDocumentRecord require(String enterprise, String resource, String id, boolean lock) {
        var doc = documents.find(enterprise, id, lock).orElseThrow(ResourceAuthorizationService::unavailable);
        if (!resource.equals(doc.resourceId()) || doc.deletedAt() != null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return doc;
    }

    private void enqueue(AuthContext actor, String document, int generation) {
        String id = UUID.randomUUID().toString();
        jobs.enqueue(id, actor.enterpriseId(), actor.userId(), "document_parse",
            "knowledge:" + document + ":" + generation,
            "{\"documentId\":\"" + document + "\",\"generation\":" + generation + "}", clock.instant());
        jobs.setAttemptLimit(id, 20);
    }

    private void revision(KnowledgeDocumentRecord doc, long expected) {
        if (doc.revision() != expected) {
            throw ApiException.versionConflict(doc.revision());
        }
    }

    private void requireProcessable(KnowledgeDocumentRecord doc) {
        if (doc.pendingGeneration() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_PROCESSING",
                "资料已经在等待或进行处理，请等待本次处理结束。");
        }
        if (doc.lastGeneration() == Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_GENERATION_LIMIT",
                "资料处理次数已达到上限，请重新上传文件。");
        }
    }
}

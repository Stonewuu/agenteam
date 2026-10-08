package com.stonewu.agenteam.service.background;

import com.stonewu.agenteam.mapper.background.PublicJobMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.PublicJobRecord;
import com.stonewu.agenteam.model.background.response.JobView;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.service.export.ExportAccessService;
import com.stonewu.agenteam.service.file.FilePurposeAuthorization;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

/**
 * 只公开文件检查、知识解析和导出；过期依据结果文件和已保存期限，不由浏览器猜测。
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicJobService {
    private final PublicJobMapper jobs;
    private final ExportAccessService exports;
    private final FileMapper files;
    private final KnowledgeDocumentMapper documents;
    private final FilePurposeAuthorization purposes;
    private final ResourceAuthorizationService resources;
    private final ResourceJson json;
    private final Clock clock;

    public PublicJobService(PublicJobMapper jobs, ExportAccessService exports, FileMapper files,
                            KnowledgeDocumentMapper documents, FilePurposeAuthorization purposes,
                            ResourceAuthorizationService resources, ResourceJson json, Clock clock) {
        this.jobs = jobs;
        this.exports = exports;
        this.files = files;
        this.documents = documents;
        this.purposes = purposes;
        this.resources = resources;
        this.json = json;
        this.clock = clock;
    }

    public JobView get(AuthContext actor, String id) {
        var job = jobs.find(actor.enterpriseId(), id).orElseThrow(ResourceAuthorizationService::unavailable);
        if (job.ownerUserId() == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (job.kind().equals("export")) {
            return export(actor, job);
        }
        var payload = json.read(job.payloadJson());
        if (job.kind().equals("file_scan")) {
            var file = files.find(actor.enterpriseId(), payload.path("fileId").asText(), false)
                .orElseThrow(ResourceAuthorizationService::unavailable);
            if (file.deletedAt() != null || (file.expiresAt() != null && !file.expiresAt().isAfter(clock.instant()))) {
                throw ResourceAuthorizationService.unavailable();
            }
            if (!file.ownerUserId().equals(actor.userId()) && (file.resourceId() == null || !Set.of("knowledge",
                "data_import").contains(file.purpose()))) {
                throw ResourceAuthorizationService.unavailable();
            }
            purposes.upload(actor, file.purpose(), file.resourceId(), false);
        } else {
            var doc = documents.find(actor.enterpriseId(), payload.path("documentId").asText(), false)
                .filter(value -> value.deletedAt() == null).orElseThrow(ResourceAuthorizationService::unavailable);
            resources.require(actor, doc.resourceId(), "knowledge.edit", ResourceCapability.EDIT);
        }
        return new JobView(job.id(), job.kind(), job.status(), null, job.errorSummary(), null, null, null,
            job.createdAt().toString(), job.updatedAt().toString());
    }

    private JobView export(AuthContext actor, PublicJobRecord job) {
        var payload = exports.readable(actor, job);
        var file = job.resultFileId() == null ? null : files.find(actor.enterpriseId(), job.resultFileId(), false)
            .orElse(null);
        if (file != null && (!file.ownerUserId().equals(actor.userId()) || !file.purpose().equals("export"))) {
            throw ExportAccessService.unavailable();
        }
        boolean expired = job.status().equals(
            "completed") && (file == null || file.deletedAt() != null || file.expiresAt() == null || !file.expiresAt()
            .isAfter(clock.instant()));
        String fileId = job.status().equals("completed") && !expired ? file.id() : null;
        String expiry = file != null && file.expiresAt() != null ? file.expiresAt().toString() : payload.expiresAt();
        return new JobView(job.id(), job.kind(), expired ? "expired" : job.status(), fileId, job.errorSummary(), expiry,
            payload.snapshotAt(), payload.rowCount(), job.createdAt().toString(), job.updatedAt().toString());
    }
}

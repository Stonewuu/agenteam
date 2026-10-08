package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.background.PublicJobMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.file.MessageAttachmentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.export.ExportAccessService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.knowledge.KnowledgeFileAccess;
import com.stonewu.agenteam.service.project.ProjectInputFileService;
import com.stonewu.agenteam.service.skill.SkillExportService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 临时上传只由原成员读取，文件编号本身不授予访问权限。
 */
@Service
public class FileAccessService {
    private final FileMapper files;
    private final FilePurposeAuthorization purposes;
    private final Clock clock;
    private final SkillExportService exports;
    private final ToolArtifactAccessService artifacts;
    private final KnowledgeFileAccess knowledge;
    private final MessageAttachmentMapper attachments;
    private final PublicJobMapper jobs;
    private final ExportAccessService dataExports;
    private final ProjectInputFileService projectInputs;

    public FileAccessService(FileMapper files, FilePurposeAuthorization purposes, Clock clock,
                             SkillExportService exports, ToolArtifactAccessService artifacts,
                             KnowledgeFileAccess knowledge, MessageAttachmentMapper attachments,
                             PublicJobMapper jobs, ExportAccessService dataExports,
                             ProjectInputFileService projectInputs) {
        this.files = files;
        this.purposes = purposes;
        this.clock = clock;
        this.exports = exports;
        this.artifacts = artifacts;
        this.knowledge = knowledge;
        this.attachments = attachments;
        this.jobs = jobs;
        this.dataExports = dataExports;
        this.projectInputs = projectInputs;
    }

    public void authorizeUpload(AuthContext actor, String purpose, String resourceId, boolean mutation) {
        purposes.upload(actor, purpose, resourceId, mutation);
    }

    public FileRecord readable(AuthContext actor, String id, boolean mutation) {
        var file = files.find(actor.enterpriseId(), id, false).orElseThrow(FileAccessService::unavailable);
        requireLive(file);
        if (file.purpose().equals("attachment") && file.expiresAt() == null && !attachments.readable(actor, file,
            clock.instant())
            && !projectInputs.contains(actor.enterpriseId(), actor.userId(), id)) {
            throw unavailable();
        }
        boolean sharedKnowledge = file.purpose().equals("knowledge") && file.expiresAt() == null && !mutation;
        if (sharedKnowledge) {
            knowledge.require(actor, file);
        } else if (!file.ownerUserId().equals(actor.userId())) {
            throw unavailable();
        } else if (FileUploadPolicy.UPLOAD_PURPOSES.contains(file.purpose())) {
            authorizeUpload(actor, file.purpose(), file.resourceId(), mutation);
        } else if (file.purpose().equals("export") && file.resourceId() != null && file.resourceVersionId() != null) {
            exports.authorize(actor, file.resourceId(), file.resourceVersionId(), mutation);
        } else if (file.purpose().equals("artifact")) {
            artifacts.require(actor, file);
        } else if (file.purpose().equals("export")) {
            dataExports.readable(actor,
                jobs.forExportFile(actor.enterpriseId(), file.id()).orElseThrow(FileAccessService::unavailable));
        } else {
            throw unavailable();
        }
        if (mutation) {
            file = files.find(actor.enterpriseId(), file.id(), true).orElseThrow(FileAccessService::unavailable);
        }
        requireLive(file);
        return file;
    }

    public FileRecord uploadOwner(AuthContext actor, String id, boolean mutation) {
        var file = readable(actor, id, mutation);
        if (!file.ownerUserId().equals(actor.userId()) || !FileUploadPolicy.UPLOAD_PURPOSES.contains(file.purpose())) {
            throw unavailable();
        }
        return file;
    }

    public FileRecord ready(AuthContext actor, String id) {
        var file = readable(actor, id, false);
        if (!file.status().equals("ready")) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_READY", "文件尚未通过检查，暂时无法使用。");
        }
        return file;
    }

    private void requireLive(FileRecord file) {
        if (file.deletedAt() != null
            || (file.expiresAt() != null && !file.expiresAt().isAfter(clock.instant()))) {
            throw unavailable();
        }
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "FILE_UNAVAILABLE", "文件不存在或已不可读取。");
    }
}

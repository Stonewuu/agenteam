package com.stonewu.agenteam.service.skill;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.skill.request.SkillImportConfirmRequest;
import com.stonewu.agenteam.model.skill.request.SkillImportPreviewRequest;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.file.FileDownloadService;
import com.stonewu.agenteam.service.file.GeneratedFileService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * 导入导出先准备可验证内容，最终事务只保存经过再次授权的结果。
 */
@Service
public class SkillTransferApiService {
    private final AuthContextService identity;
    private final IdempotentRequestService requests;
    private final SkillImportService imports;
    private final SkillExportService exports;
    private final GeneratedFileService files;
    private final FileDownloadService downloads;
    private final AuditEventService audit;

    public SkillTransferApiService(AuthContextService identity, IdempotentRequestService requests,
                                   SkillImportService imports, SkillExportService exports, GeneratedFileService files,
                                   FileDownloadService downloads, AuditEventService audit) {
        this.identity = identity;
        this.requests = requests;
        this.imports = imports;
        this.exports = exports;
        this.files = files;
        this.downloads = downloads;
        this.audit = audit;
    }

    public ApiOperationResult preview(String enterprise, String fileId, HttpServletRequest request) {
        InputValidation.request(request, SkillImportPreviewRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> imports.authorize(identity.requireEnterprise(request.getSession(false), enterprise),
            false);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var preview = imports.preview(actor, fileId);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize, () -> {
            imports.verifyPreview(actor, preview.previewToken(), true);
            return ApiOperationResult.of(200, preview);
        });
    }

    public ApiOperationResult confirm(String enterprise, SkillImportConfirmRequest input, HttpServletRequest request) {
        InputValidation.request(request, SkillImportConfirmRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> imports.authorize(identity.requireEnterprise(request.getSession(false), enterprise),
            true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var prepared = imports.prepare(actor, input);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize,
            () -> ApiOperationResult.of(201, imports.confirm(actor, input, prepared)));
    }

    public ApiOperationResult export(String enterprise, String resourceId, String versionId,
                                     HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        Runnable authorize = () -> exports.authorize(identity.requireEnterprise(request.getSession(false), enterprise),
            resourceId, versionId, true);
        var replay = requests.replay(request, actor.user(), enterprise, Set.of(), authorize);
        if (replay.isPresent()) {
            return replay.get();
        }
        var prepared = exports.prepare(actor, resourceId, versionId);
        return requests.execute(request, actor.user(), enterprise, Set.of(), authorize, () -> {
            var file = files.register(prepared);
            audit.record(actor.enterpriseId(), actor.user(), "skill.export", "resource", prepared.resourceId(),
                "导出技能固定版本",
                Map.of("versionId", prepared.resourceVersionId(), "fileId", file.id()));
            return ApiOperationResult.of(200, downloads.issue(actor, file.id()));
        });
    }
}

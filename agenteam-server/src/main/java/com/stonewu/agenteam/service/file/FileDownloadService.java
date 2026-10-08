package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.file.response.FileDownloadView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.Map;

/**
 * 下载记录真实访问，内容发送前重新检查文件资格与下载时限。
 */
@Service
public class FileDownloadService {
    private final FileAccessService access;
    private final FileDownloadTokenService tokens;
    private final FileContentStorage storage;
    private final AuditEventService audit;
    private final String origin;

    public FileDownloadService(FileAccessService access, FileDownloadTokenService tokens, FileContentStorage storage,
                               AuditEventService audit,
                               @Value("${agenteam.web.public-base-url:http://localhost:3000}") String origin) {
        this.access = access;
        this.tokens = tokens;
        this.storage = storage;
        this.audit = audit;
        this.origin = origin.replaceAll("/$", "");
    }

    @Transactional
    public FileDownloadView issue(AuthContext actor, String id) {
        var file = access.ready(actor, id);
        var token = tokens.issue(actor, file);
        audit.record(actor.enterpriseId(), actor.user(), "file.download", "file", file.id(), "获取文件下载地址",
            Map.of("purpose", file.purpose()));
        return new FileDownloadView(
            origin + "/api/v1/enterprises/" + file.enterpriseId() + "/files/" + file.id() + "/content?token=" + token.value(),
            token.expiresAt().toString(), file.originalName());
    }

    public FileRecord verify(AuthContext actor, String id, String token) {
        var file = access.ready(actor, id);
        tokens.verify(token, actor, file);
        return file;
    }

    public InputStream open(AuthContext actor, String id, String token) {
        return storage.open(verify(actor, id, token));
    }
}

package com.stonewu.agenteam.service.knowledge;

import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.file.FileAccessService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

/**
 * 已加入知识库的文件由当前使用授权决定访问，撤权或删除资料后下载签名也失效。
 */
@Service
public class KnowledgeFileAccess {
    private final ResourceAuthorizationService access;
    private final KnowledgeDocumentMapper documents;

    public KnowledgeFileAccess(ResourceAuthorizationService access, KnowledgeDocumentMapper documents) {
        this.access = access;
        this.documents = documents;
    }

    public void require(AuthContext actor, FileRecord file) {
        if (file.resourceId() == null) {
            throw FileAccessService.unavailable();
        }
        access.requireUse(actor, file.resourceId(), "knowledge");
        if (!documents.referencesReadableFile(actor.enterpriseId(), file.resourceId(), file.id())) {
            throw FileAccessService.unavailable();
        }
    }
}

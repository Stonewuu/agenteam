package com.stonewu.agenteam.service.knowledge;

import com.stonewu.agenteam.mapper.knowledge.KnowledgeChunkMapper;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.knowledge.response.CitationView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 明确选择的引用只能读取真实完成过的版本，客户端不能伪造片段或来源。
 */
@Service
public class KnowledgeReferenceService {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeChunkMapper chunks;
    private final ResourceAuthorizationService access;

    public KnowledgeReferenceService(KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks,
                                     ResourceAuthorizationService access) {
        this.documents = documents;
        this.chunks = chunks;
        this.access = access;
    }

    public List<CitationView> read(AuthContext actor, String document, int generation) {
        return read(actor, document, generation, null);
    }

    public CitationView original(AuthContext actor, String chunk) {
        var source = chunks.original(actor.enterpriseId(), chunk)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        access.requireUse(actor, source.resourceId(), "knowledge");
        return source.citation();
    }

    public List<CitationView> read(AuthContext actor, String document, int generation, Set<String> allowedResources) {
        var doc = documents.find(actor.enterpriseId(), document, false).filter(row -> row.deletedAt() == null)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (allowedResources != null && !allowedResources.contains(doc.resourceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "KNOWLEDGE_REFERENCE_NOT_CONFIGURED",
                "这个员工尚未配置所选资料，请选择可用资料或联系维护者。");
        }
        access.requireUse(actor, doc.resourceId(), "knowledge");
        if (generation < 1 || generation > doc.activeGeneration()) {
            throw unavailable();
        }
        var result = chunks.reference(actor.enterpriseId(), document, generation);
        if (result.isEmpty()) {
            throw unavailable();
        }
        return result;
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "KNOWLEDGE_REFERENCE_UNAVAILABLE",
            "所选资料版本尚未完成或已不可读取，请重新选择。");
    }
}

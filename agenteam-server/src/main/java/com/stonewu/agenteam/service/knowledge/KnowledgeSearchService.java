package com.stonewu.agenteam.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.knowledge.KnowledgeChunkMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.knowledge.request.KnowledgeQueryRequest;
import com.stonewu.agenteam.model.knowledge.response.CitationView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索前后验证当前使用资格，固定配置只能缩小读取范围。
 */
@Service
public class KnowledgeSearchService {
    private final ResourceAuthorizationService access;
    private final ResourceMapper resources;
    private final KnowledgeChunkMapper chunks;

    public KnowledgeSearchService(ResourceAuthorizationService access, ResourceMapper resources,
                                  KnowledgeChunkMapper chunks) {
        this.access = access;
        this.resources = resources;
        this.chunks = chunks;
    }

    public List<CitationView> search(AuthContext actor, String resource, KnowledgeQueryRequest input) {
        return search(actor, resource, input, null);
    }

    public List<CitationView> search(AuthContext actor, String resource, KnowledgeQueryRequest input,
                                     JsonNode fixedConfig) {
        resource = access.requireUse(actor, resource, "knowledge").id();
        String query = input.query() == null ? "" : input.query().trim();
        int size = query.codePointCount(0, query.length());
        if (size < 2 || size > 500 || query.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("query", "请输入二至五百个字符的检索内容。");
        }
        int requested = input.limit() == null ? 8 : input.limit();
        if (requested < 1 || requested > 8) {
            throw ApiException.invalidField("limit", "每次最多读取八段资料。");
        }
        var config = fixedConfig == null ? resources.find(actor.enterpriseId(), resource, false, false)
            .orElseThrow(ResourceAuthorizationService::unavailable).config() : fixedConfig;
        int maximum = Math.max(1, Math.min(8, config.path("maxResults").asInt(8)));
        int characters = Math.max(1, Math.min(8000, config.path("maxContextCharacters").asInt(8000)));
        List<CitationView> result = new ArrayList<>();
        for (var citation : chunks.search(actor.enterpriseId(), resource, query, Math.min(requested, maximum))) {
            int count = citation.excerpt().codePointCount(0, citation.excerpt().length());
            if (characters < count) {
                result.add(new CitationView(citation.chunkId(), citation.documentId(), citation.fileId(),
                    citation.generation(), citation.name(), citation.page(), citation.section(),
                    citation.excerpt().substring(0, citation.excerpt().offsetByCodePoints(0, characters))));
                break;
            }
            result.add(citation);
            characters -= count;
            if (characters == 0) {
                break;
            }
        }
        access.requireUse(actor, resource, "knowledge");
        return List.copyOf(result);
    }
}

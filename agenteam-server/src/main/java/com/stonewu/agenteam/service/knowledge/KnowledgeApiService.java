package com.stonewu.agenteam.service.knowledge;

import com.stonewu.agenteam.mapper.knowledge.KnowledgeDocumentMapper;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.knowledge.request.DocumentCreateRequest;
import com.stonewu.agenteam.model.knowledge.request.DocumentReplaceRequest;
import com.stonewu.agenteam.model.knowledge.request.KnowledgeQueryRequest;
import com.stonewu.agenteam.model.knowledge.response.CitationView;
import com.stonewu.agenteam.model.knowledge.response.KnowledgeDocumentView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识接口复用当前企业身份、重复请求记录、修改版本和分页签名。
 */
@Service
public class KnowledgeApiService {
    private final AuthContextService identity;
    private final KnowledgeDocumentService documents;
    private final KnowledgeDocumentMapper records;
    private final KnowledgeSearchService search;
    private final IdempotentRequestService requests;
    private final ListPagination pagination;

    public KnowledgeApiService(AuthContextService identity, KnowledgeDocumentService documents,
                               KnowledgeDocumentMapper records, KnowledgeSearchService search,
                               IdempotentRequestService requests, ListPagination pagination) {
        this.identity = identity;
        this.documents = documents;
        this.records = records;
        this.search = search;
        this.requests = requests;
        this.pagination = pagination;
    }

    public PageResponse<KnowledgeDocumentView> list(String enterprise, String resource, String query, Integer count,
                                                    String cursor, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        documents.authorize(actor, resource, false);
        String term = pagination.query(query);
        int limit = pagination.limit(count);
        var binding = new ListPagination.Binding(actor.userId(), enterprise, "knowledge:" + resource, term,
            "updated_desc");
        var page = records.list(enterprise, resource, term, pagination.read(cursor, binding), limit).stream()
            .map(KnowledgeDocumentMapper::view).toList();
        return pagination.page(page, limit, binding, row -> new PagePosition(Instant.parse(row.updatedAt()), row.id()));
    }

    public ApiOperationResult create(String enterprise, String resource, DocumentCreateRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, DocumentCreateRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> documents.authorize(identity.requireEnterprise(request.getSession(false), enterprise), resource,
                true),
            () -> ApiOperationResult.of(202, documents.create(actor, resource, input.fileIds())));
    }

    public ApiOperationResult reprocess(String enterprise, String resource, String id, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        long revision = RequestPreconditions.revision(request);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> documents.authorize(identity.requireEnterprise(request.getSession(false), enterprise), resource,
                true),
            () -> ApiOperationResult.of(202, documents.reprocess(actor, resource, id, revision)));
    }

    public ApiOperationResult replaceFile(String enterprise, String resource, String id, DocumentReplaceRequest input,
                                          HttpServletRequest request) {
        InputValidation.request(request, DocumentReplaceRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        long revision = RequestPreconditions.revision(request);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> documents.authorize(identity.requireEnterprise(request.getSession(false), enterprise), resource,
                true),
            () -> ApiOperationResult.of(202, documents.replaceFile(actor, resource, id, input.fileId(), revision)));
    }

    public ApiOperationResult delete(String enterprise, String resource, String id, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        long revision = RequestPreconditions.revision(request);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> documents.authorize(identity.requireEnterprise(request.getSession(false), enterprise), resource,
                true), () -> {
                documents.delete(actor, resource, id, revision);
                return ApiOperationResult.of(200, Map.of("success", true));
            });
    }

    public List<CitationView> search(String enterprise, String resource, KnowledgeQueryRequest input,
                                     HttpServletRequest request) {
        InputValidation.request(request, KnowledgeQueryRequest.class);
        return search.search(identity.requireEnterprise(request.getSession(false), enterprise), resource, input);
    }
}

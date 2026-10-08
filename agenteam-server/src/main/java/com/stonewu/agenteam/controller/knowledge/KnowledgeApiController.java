package com.stonewu.agenteam.controller.knowledge;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.knowledge.request.DocumentCreateRequest;
import com.stonewu.agenteam.model.knowledge.request.DocumentReplaceRequest;
import com.stonewu.agenteam.model.knowledge.request.KnowledgeQueryRequest;
import com.stonewu.agenteam.model.knowledge.response.CitationView;
import com.stonewu.agenteam.model.knowledge.response.KnowledgeDocumentView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.knowledge.KnowledgeApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识资料登记、处理状态和检索入口。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/knowledge/{resourceId}")
public class KnowledgeApiController {

    private final KnowledgeApiService knowledge;

    private final ApiResponses responses;

    public KnowledgeApiController(KnowledgeApiService knowledge, ApiResponses responses) {
        this.knowledge = knowledge;
        this.responses = responses;
    }

    @GetMapping("/documents")
    public ApiResponse<PageResponse<KnowledgeDocumentView>> list(@PathVariable String enterpriseId,
                                                                 @PathVariable String resourceId,
                                                                 @RequestParam(required = false) String query,
                                                                 @RequestParam(required = false) Integer limit,
                                                                 @RequestParam(required = false) String cursor,
                                                                 HttpServletRequest request) {
        return responses.success(knowledge.list(enterpriseId, resourceId, query, limit, cursor, request), request);
    }

    @PostMapping("/documents")
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId,
                                                      @RequestBody DocumentCreateRequest body,
                                                      HttpServletRequest request) {
        return responses.operation(knowledge.create(enterpriseId, resourceId, body, request), request);
    }

    @PostMapping("/documents/{documentId}/reprocess")
    public ResponseEntity<ApiResponse<Object>> reprocess(@PathVariable String enterpriseId,
                                                         @PathVariable String resourceId,
                                                         @PathVariable String documentId, HttpServletRequest request) {
        return responses.operation(knowledge.reprocess(enterpriseId, resourceId, documentId, request), request);
    }

    @PostMapping("/documents/{documentId}/replace-file")
    public ResponseEntity<ApiResponse<Object>> replaceFile(@PathVariable String enterpriseId,
                                                           @PathVariable String resourceId,
                                                           @PathVariable String documentId,
                                                           @RequestBody DocumentReplaceRequest body,
                                                           HttpServletRequest request) {
        return responses.operation(knowledge.replaceFile(enterpriseId, resourceId, documentId, body, request), request);
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<Object>> delete(@PathVariable String enterpriseId,
                                                      @PathVariable String resourceId, @PathVariable String documentId,
                                                      HttpServletRequest request) {
        return responses.operation(knowledge.delete(enterpriseId, resourceId, documentId, request), request);
    }

    @PostMapping("/search")
    public ApiResponse<List<CitationView>> search(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                                  @RequestBody KnowledgeQueryRequest body, HttpServletRequest request) {
        return responses.success(knowledge.search(enterpriseId, resourceId, body, request), request);
    }
}

package com.stonewu.agenteam.controller.knowledge;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.knowledge.response.CitationView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.knowledge.KnowledgeReferenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原文由服务端根据真实文本块读取，不接受浏览器提交的文档名或引用片段。
 */
@RestController
public class KnowledgeCitationController {

    private final AuthContextService identity;

    private final KnowledgeReferenceService references;

    private final ApiResponses responses;

    public KnowledgeCitationController(AuthContextService identity, KnowledgeReferenceService references,
                                       ApiResponses responses) {
        this.identity = identity;
        this.references = references;
        this.responses = responses;
    }

    @GetMapping("/api/v1/enterprises/{enterpriseId}/knowledge/citations/{chunkId}")
    public ApiResponse<CitationView> original(@PathVariable String enterpriseId, @PathVariable String chunkId,
                                              HttpServletRequest request) {
        return responses.success(
            references.original(identity.requireEnterprise(request.getSession(false), enterpriseId), chunkId), request);
    }
}

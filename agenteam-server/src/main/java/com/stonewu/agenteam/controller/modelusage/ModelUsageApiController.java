package com.stonewu.agenteam.controller.modelusage;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.modelusage.request.ModelUsageQueryRequest;
import com.stonewu.agenteam.model.modelusage.response.ConversationModelUsageView;
import com.stonewu.agenteam.model.modelusage.response.ModelCallView;
import com.stonewu.agenteam.model.modelusage.response.ModelUsageSummaryView;
import com.stonewu.agenteam.model.modelusage.response.ModelUsageOptionView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.modelusage.ModelUsageQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ModelUsageApiController {
    private final AuthContextService identity;
    private final ModelUsageQueryService service;
    private final ApiResponses responses;

    public ModelUsageApiController(AuthContextService identity, ModelUsageQueryService service, ApiResponses responses) {
        this.identity = identity;
        this.service = service;
        this.responses = responses;
    }

    @GetMapping("/usage/models")
    public ApiResponse<ModelUsageSummaryView> summary(@PathVariable String enterpriseId, @ModelAttribute ModelUsageQueryRequest query,
                                                       HttpServletRequest request) {
        return responses.success(service.summary(identity.requireEnterprise(request.getSession(false), enterpriseId), query), request);
    }

    @GetMapping("/usage/model-options")
    public ApiResponse<List<ModelUsageOptionView>> options(@PathVariable String enterpriseId, @ModelAttribute ModelUsageQueryRequest filter,
                                                            @RequestParam String kind, @RequestParam(required = false) String query,
                                                            HttpServletRequest request) {
        return responses.success(service.options(identity.requireEnterprise(request.getSession(false), enterpriseId), filter, kind, query), request);
    }

    @GetMapping("/usage/model-calls")
    public ApiResponse<PageResponse<ModelCallView>> calls(@PathVariable String enterpriseId, @ModelAttribute ModelUsageQueryRequest query,
                                                           HttpServletRequest request) {
        return responses.success(service.calls(identity.requireEnterprise(request.getSession(false), enterpriseId), query), request);
    }

    @GetMapping("/usage/model-calls/{callId}")
    public ApiResponse<ModelCallView> detail(@PathVariable String enterpriseId, @PathVariable String callId, HttpServletRequest request) {
        return responses.success(service.detail(identity.requireEnterprise(request.getSession(false), enterpriseId), callId), request);
    }

    @GetMapping("/conversations/{conversationId}/model-usage")
    public ApiResponse<ConversationModelUsageView> conversation(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                                                @RequestParam(required = false) String runId,
                                                                @RequestParam(required = false) String cursor,
                                                                @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(service.conversation(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId, runId, cursor, limit), request);
    }
}

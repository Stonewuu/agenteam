package com.stonewu.agenteam.controller.usage;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.usage.response.UsageView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.usage.UsageQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 基础用量读取不依赖商业次数规则管理。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/usage")
public class UsageApiController {
    private final AuthContextService identity;
    private final UsageQueryService queries;
    private final ApiResponses responses;

    public UsageApiController(AuthContextService identity, UsageQueryService queries, ApiResponses responses) {
        this.identity = identity;
        this.queries = queries;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<UsageView> usage(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(queries.usage(identity.requireEnterprise(request.getSession(false), enterpriseId)), request);
    }
}

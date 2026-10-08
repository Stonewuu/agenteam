package com.stonewu.agenteam.controller.workspace;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.workspace.response.HomeSummaryView;
import com.stonewu.agenteam.model.workspace.response.SearchResultView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.workspace.HomeQueryService;
import com.stonewu.agenteam.service.workspace.WorkspaceSearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class WorkspaceApiController {

    private final AuthContextService identity;

    private final ApiResponses responses;

    private final HomeQueryService home;

    private final WorkspaceSearchService search;

    public WorkspaceApiController(AuthContextService identity, ApiResponses responses, HomeQueryService home,
                                  WorkspaceSearchService search) {
        this.identity = identity;
        this.responses = responses;
        this.home = home;
        this.search = search;
    }

    @GetMapping("/home")
    public ApiResponse<HomeSummaryView> home(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(home.get(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }

    @GetMapping("/search")
    public ApiResponse<SearchResultView> search(@PathVariable String enterpriseId,
                                                @RequestParam(required = false) String query,
                                                HttpServletRequest request) {
        return responses.success(search.get(identity.requireEnterprise(request.getSession(false), enterpriseId), query),
            request);
    }
}

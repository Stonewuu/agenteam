package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.resource.UsableVersionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/resources/usable-versions")
public class UsableVersionApiController {

    private final UsableVersionService versions;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public UsableVersionApiController(UsableVersionService versions, AuthContextService identity,
                                      ApiResponses responses) {
        this.versions = versions;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<UsableVersionView>> list(@PathVariable String enterpriseId,
                                                             @RequestParam String kind,
                                                             @RequestParam(required = false) String query,
                                                             @RequestParam(required = false) String cursor,
                                                             @RequestParam(required = false) Integer limit,
                                                             @RequestParam(required = false) List<String> versionIds,
                                                             @RequestParam(defaultValue = "false") boolean subagentsOnly,
                                                             @RequestParam(required = false) String resourceId,
                                                             @RequestParam(defaultValue = "false") boolean latestOnly,
                                                             HttpServletRequest request) {
        return responses.success(
            versions.list(identity.requireEnterprise(request.getSession(false), enterpriseId), kind, query, cursor,
                limit, versionIds, subagentsOnly, resourceId, latestOnly), request);
    }
}

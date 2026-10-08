package com.stonewu.agenteam.controller.resource;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.response.ResourceSubjectOption;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.resource.ResourceSubjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/resources/{resourceId}/subjects")
public class ResourceSubjectApiController {

    private final ResourceSubjectService subjects;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public ResourceSubjectApiController(ResourceSubjectService subjects, AuthContextService identity,
                                        ApiResponses responses) {
        this.subjects = subjects;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<ResourceSubjectOption>> list(@PathVariable String enterpriseId,
                                                                 @PathVariable String resourceId,
                                                                 @RequestParam String subjectType,
                                                                 @RequestParam(required = false) String purpose,
                                                                 @RequestParam(required = false) String query,
                                                                 @RequestParam(required = false) List<String> subjectIds,
                                                                 @RequestParam(required = false) String cursor,
                                                                 @RequestParam(required = false) Integer limit,
                                                                 HttpServletRequest request) {
        return responses.success(
            subjects.list(identity.requireEnterprise(request.getSession(false), enterpriseId), resourceId, subjectType,
                purpose, query, subjectIds, cursor, limit), request);
    }
}

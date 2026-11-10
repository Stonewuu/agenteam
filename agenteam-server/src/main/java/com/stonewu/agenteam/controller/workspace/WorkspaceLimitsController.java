package com.stonewu.agenteam.controller.workspace;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.workspace.request.WorkspaceLimitsRequest;
import com.stonewu.agenteam.model.workspace.response.WorkspaceLimitsView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.http.RequestPreconditions;
import com.stonewu.agenteam.service.workspace.WorkspaceLimitsManagement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作空间限制的系统管理接口。 */
@RestController
@RequestMapping("/api/v1/system/workspace-limits")
public class WorkspaceLimitsController {
    private final AuthContextService identity;
    private final WorkspaceLimitsManagement management;
    private final ApiResponses responses;

    public WorkspaceLimitsController(AuthContextService identity, WorkspaceLimitsManagement management,
                                      ApiResponses responses) {
        this.identity = identity;
        this.management = management;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<WorkspaceLimitsView> read(HttpServletRequest request) {
        return responses.success(management.read(identity.requireUser(request.getSession(false))), request);
    }

    @PutMapping
    public ApiResponse<WorkspaceLimitsView> save(@RequestBody WorkspaceLimitsRequest input,
                                                HttpServletRequest request) {
        var actor = identity.requireUser(request.getSession(false));
        InputValidation.request(request, WorkspaceLimitsRequest.class);
        return responses.success(management.save(actor, RequestPreconditions.revision(request), input), request);
    }
}

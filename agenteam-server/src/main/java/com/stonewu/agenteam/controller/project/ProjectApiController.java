package com.stonewu.agenteam.controller.project;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.project.request.CreateProjectInput;
import com.stonewu.agenteam.model.project.request.SelectProjectInput;
import com.stonewu.agenteam.model.project.response.ProjectView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.project.ConversationProjectService;
import com.stonewu.agenteam.service.project.ProjectApiService;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 用户可以选择或创建项目，会话切换仅修改目录关联。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class ProjectApiController {

    private final AuthContextService identity;

    private final ProjectMetadataService projects;

    private final ConversationProjectService conversations;

    private final ProjectApiService mutations;

    private final ApiResponses responses;

    public ProjectApiController(AuthContextService identity, ProjectMetadataService projects,
                                ConversationProjectService conversations, ProjectApiService mutations,
                                ApiResponses responses) {
        this.identity = identity;
        this.projects = projects;
        this.conversations = conversations;
        this.mutations = mutations;
        this.responses = responses;
    }

    @GetMapping("/projects")
    public ApiResponse<PageResponse<ProjectView>> list(@PathVariable String enterpriseId,
                                                       @RequestParam(required = false) String query,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit,
                                                       HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return responses.success(projects.list(actor, query, cursor, limit), request);
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectView> read(@PathVariable String enterpriseId, @PathVariable String projectId,
                                         HttpServletRequest request) {
        return responses.success(
            projects.view(identity.requireEnterprise(request.getSession(false), enterpriseId), projectId), request);
    }

    @PostMapping("/projects")
    public ResponseEntity<?> create(@PathVariable String enterpriseId, @RequestBody CreateProjectInput input,
                                    HttpServletRequest request) {
        return responses.operation(mutations.create(enterpriseId, input, request), request);
    }

    @GetMapping("/conversations/{conversationId}/project")
    public ApiResponse<ProjectView> current(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                            HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        return responses.success(conversations.current(actor, conversationId), request);
    }

    @PutMapping("/conversations/{conversationId}/project")
    public ResponseEntity<?> select(@PathVariable String enterpriseId, @PathVariable String conversationId,
                                    @RequestBody SelectProjectInput input, HttpServletRequest request) {
        return responses.operation(mutations.select(enterpriseId, conversationId, input, request), request);
    }
}

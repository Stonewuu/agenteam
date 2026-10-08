package com.stonewu.agenteam.controller.skill;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.skill.response.WorkspaceSkillView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.skill.WorkspaceSkillService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 工作台读取当前成员可以交给已雇佣员工执行的公开技能候选。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class WorkspaceSkillController {

    private final AuthContextService identity;

    private final WorkspaceSkillService workspace;

    private final ApiResponses responses;

    public WorkspaceSkillController(AuthContextService identity, WorkspaceSkillService workspace,
                                    ApiResponses responses) {
        this.identity = identity;
        this.workspace = workspace;
        this.responses = responses;
    }

    @GetMapping("/workspace/skills")
    public ApiResponse<PageResponse<WorkspaceSkillView>> workspace(@PathVariable String enterpriseId,
                                                                   @RequestParam(required = false) String query,
                                                                   @RequestParam(required = false) String cursor,
                                                                   @RequestParam(required = false) Integer limit,
                                                                   HttpServletRequest request) {
        return responses.success(
            workspace.list(identity.requireEnterprise(request.getSession(false), enterpriseId), query, cursor, limit),
            request);
    }
}

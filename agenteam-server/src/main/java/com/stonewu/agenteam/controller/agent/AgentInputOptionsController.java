package com.stonewu.agenteam.controller.agent;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.agent.AgentInputOptionsService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话输入候选来自当前会话固定能力，控制器不读取或推断配置。
 */
@RestController
public class AgentInputOptionsController {

    private final AuthContextService identity;

    private final AgentInputOptionsService inputs;

    private final ApiResponses responses;

    public AgentInputOptionsController(AuthContextService identity, AgentInputOptionsService inputs,
                                       ApiResponses responses) {
        this.identity = identity;
        this.inputs = inputs;
        this.responses = responses;
    }

    @GetMapping("/api/v1/enterprises/{enterpriseId}/agents/{agentId}/input-options")
    public ApiResponse<PageResponse<?>> input(@PathVariable String enterpriseId, @PathVariable String agentId,
                                              @RequestParam String kind,
                                              @RequestParam(required = false) String conversationId,
                                              @RequestParam(required = false) String query,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) Integer limit,
                                              HttpServletRequest request) {
        return responses.success(
            inputs.list(identity.requireEnterprise(request.getSession(false), enterpriseId), agentId, conversationId,
                kind, query, cursor, limit), request);
    }
}

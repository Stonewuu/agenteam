package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.ConversationNavigationService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 对话列表与当前岗位的导航信息，不改变原对话读取地址。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/conversations")
public class ConversationNavigationController {
    private final ConversationNavigationService navigation;
    private final AuthContextService identity;
    private final ApiResponses responses;

    public ConversationNavigationController(ConversationNavigationService navigation, AuthContextService identity, ApiResponses responses) {
        this.navigation = navigation;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping("/navigation")
    public ApiResponse<?> list(@PathVariable String enterpriseId, @RequestParam(required = false) String query,
        @RequestParam(required = false) String status, @RequestParam(required = false) Boolean favorite,
        @RequestParam(required = false) String agentId, @RequestParam(required = false) String scope,
        @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(navigation.list(identity.requireEnterprise(request.getSession(false), enterpriseId),
            query, status, favorite, agentId, scope, cursor, limit), request);
    }

    @GetMapping("/{conversationId}/navigation")
    public ApiResponse<?> current(@PathVariable String enterpriseId, @PathVariable String conversationId, HttpServletRequest request) {
        return responses.success(navigation.current(identity.requireEnterprise(request.getSession(false), enterpriseId), conversationId), request);
    }
}

package com.stonewu.agenteam.controller.workspace;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.workspace.response.ActivityTrendView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.workspace.ActivityTrendService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动接口的用户身份来自登录会话，不接受客户端指定其他用户。
 */
@RestController
public class ActivityTrendController {

    private final AuthContextService identity;

    private final ApiResponses responses;

    private final ActivityTrendService activities;

    public ActivityTrendController(AuthContextService identity, ApiResponses responses,
                                   ActivityTrendService activities) {
        this.identity = identity;
        this.responses = responses;
        this.activities = activities;
    }

    @GetMapping("/api/v1/enterprises/{enterpriseId}/home/activity")
    public ApiResponse<ActivityTrendView> get(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(activities.get(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }
}

package com.stonewu.agenteam.controller.http;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.ApplicationInfoResponse;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.ApplicationInfoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已登录用户可以查看当前运行版本，接口不公开部署配置。 */
@RestController
public class ApplicationInfoController {
    private final AuthContextService identity;
    private final ApplicationInfoService information;
    private final ApiResponses responses;

    public ApplicationInfoController(AuthContextService identity, ApplicationInfoService information,
                                      ApiResponses responses) {
        this.identity = identity;
        this.information = information;
        this.responses = responses;
    }

    @GetMapping("/api/v1/application")
    public ApiResponse<ApplicationInfoResponse> current(HttpServletRequest request) {
        identity.requireUser(request.getSession(false));
        return responses.success(information.current(), request);
    }
}

package com.stonewu.agenteam.controller.auth;

import com.stonewu.agenteam.model.auth.request.ImpersonationStartRequest;
import com.stonewu.agenteam.model.auth.response.ImpersonationStatus;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.ImpersonationService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 身份切换入口不依赖目标账号仍然拥有企业页面权限。 */
@RestController
@RequestMapping("/api/v1/auth/impersonation")
public class ImpersonationApiController {
    private final ImpersonationService service;
    private final ApiResponses responses;

    public ImpersonationApiController(ImpersonationService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<ImpersonationStatus> status(HttpServletRequest request) {
        return responses.success(service.status(request.getSession(false)), request);
    }

    @PostMapping("/start")
    public ApiResponse<ImpersonationStatus> start(@RequestBody ImpersonationStartRequest body, HttpServletRequest request) {
        return responses.success(service.start(body, request), request);
    }

    @PostMapping("/stop")
    public ApiResponse<ImpersonationStatus> stop(HttpServletRequest request) {
        return responses.success(service.stop(request), request);
    }
}

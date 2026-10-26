package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.integration.feishu.FeishuManagementAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理授权完成后跳回不含授权码的页面。 */
@RestController
public class FeishuManagementAuthorizationController {
    private final FeishuManagementAuthorizationService service;
    private final ApiResponses responses;

    public FeishuManagementAuthorizationController(FeishuManagementAuthorizationService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping("/api/v1/auth/feishu-management-callbacks/{connectionId}")
    public ResponseEntity<Void> callback(@PathVariable String connectionId, @RequestParam(required = false) String state,
            @RequestParam(required = false) String code, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(service.callback(connectionId, state, code, request)).build();
    }

    @GetMapping("/api/v1/auth/feishu-management-result")
    public ApiResponse<Object> result(HttpServletRequest request) {
        return responses.success(service.result(request), request);
    }
}

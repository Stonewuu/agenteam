package com.stonewu.agenteam.controller.enterprise;

import com.stonewu.agenteam.model.enterprise.response.EnterpriseContextResponse;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.enterprise.EnterpriseContextService;
import com.stonewu.agenteam.service.enterprise.EnterpriseSelectionService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 企业页面按自己的路径读取身份，不切换共享会话中的企业偏好。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}")
public class EnterpriseContextApiController {

    private final EnterpriseContextService context;

    private final ApiResponses responses;

    private final EnterpriseSelectionService selection;

    public EnterpriseContextApiController(EnterpriseContextService context, ApiResponses responses,
                                          EnterpriseSelectionService selection) {
        this.context = context;
        this.responses = responses;
        this.selection = selection;
    }

    @GetMapping("/context")
    public ApiResponse<EnterpriseContextResponse> get(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(context.get(request.getSession(false), enterpriseId), request);
    }

    @PostMapping("/select")
    public ResponseEntity<ApiResponse<Object>> select(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.operation(selection.select(enterpriseId, request), request);
    }
}

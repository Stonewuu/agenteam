package com.stonewu.agenteam.controller.workflow;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.workflow.response.WorkflowValidationView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.workflow.WorkflowApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 返回工作流实际校验结果，业务授权和图处理由服务完成。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/workflows/{resourceId}")
public class WorkflowApiController {

    private final WorkflowApiService workflows;

    private final ApiResponses responses;

    public WorkflowApiController(WorkflowApiService workflows, ApiResponses responses) {
        this.workflows = workflows;
        this.responses = responses;
    }

    @PostMapping("/validate")
    public ApiResponse<WorkflowValidationView> validate(@PathVariable String enterpriseId,
                                                        @PathVariable String resourceId, HttpServletRequest request) {
        return responses.success(workflows.validate(enterpriseId, resourceId, request), request);
    }

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@PathVariable String enterpriseId, @PathVariable String resourceId,
                                     HttpServletRequest request) {
        return responses.operation(workflows.preview(enterpriseId, resourceId, request), request);
    }
}

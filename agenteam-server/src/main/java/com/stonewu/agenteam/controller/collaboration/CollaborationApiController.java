package com.stonewu.agenteam.controller.collaboration;

import java.util.Map;
import com.stonewu.agenteam.model.collaboration.request.CollaborationActionRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationInputRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationReplyModeRequest;
import com.stonewu.agenteam.model.collaboration.request.CollaborationResultResendRequest;
import com.stonewu.agenteam.model.collaboration.response.CollaborationReplyModeView;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskDetail;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskSummary;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.collaboration.CollaborationApiService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 协作正文和动作只提供成员本人入口，系统管理入口不能代成员执行。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/collaboration/tasks")
public class CollaborationApiController {
    private final CollaborationApiService service;
    private final ApiResponses responses;

    public CollaborationApiController(CollaborationApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<CollaborationTaskSummary>> list(@PathVariable String enterpriseId,
            @RequestParam(required = false) String conversationId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, conversationId, status, query, cursor, limit, request), request);
    }

    @GetMapping("/{id}")
    public ApiResponse<CollaborationTaskDetail> detail(@PathVariable String enterpriseId, @PathVariable String id, HttpServletRequest request) {
        return responses.success(service.detail(enterpriseId, id, request), request);
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<PageResponse<Map<String, Object>>> messages(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(service.messages(enterpriseId, id, cursor, limit, request), request);
    }

    @GetMapping("/{id}/reply-mode")
    public ApiResponse<CollaborationReplyModeView> replyMode(@PathVariable String enterpriseId, @PathVariable String id, HttpServletRequest request) {
        return responses.success(service.replyMode(enterpriseId, id, request), request);
    }

    @PostMapping("/{id}/reply-mode")
    public ResponseEntity<ApiResponse<Object>> replyMode(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody CollaborationReplyModeRequest input, HttpServletRequest request) {
        return responses.operation(service.replyMode(enterpriseId, id, input, request), request);
    }

    @PostMapping("/{id}/resend-result")
    public ResponseEntity<ApiResponse<Object>> resendResult(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody CollaborationResultResendRequest input, HttpServletRequest request) {
        return responses.operation(service.resendResult(enterpriseId, id, input, request), request);
    }

    @GetMapping("/{id}/artifacts/{artifactId}")
    public ApiResponse<Map<String, Object>> artifact(@PathVariable String enterpriseId, @PathVariable String id, @PathVariable String artifactId,
            @RequestParam int version, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "8000") int limit, HttpServletRequest request) {
        return responses.success(service.artifact(enterpriseId, id, artifactId, version, offset, limit, request), request);
    }

    @PostMapping("/{id}/inputs")
    public ResponseEntity<ApiResponse<Object>> input(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody CollaborationInputRequest input, HttpServletRequest request) {
        return responses.operation(service.input(enterpriseId, id, input, request), request);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Object>> cancel(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody CollaborationActionRequest input, HttpServletRequest request) {
        return responses.operation(service.cancel(enterpriseId, id, input, request), request);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<Object>> retry(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody CollaborationActionRequest input, HttpServletRequest request) {
        return responses.operation(service.retry(enterpriseId, id, input, request), request);
    }
}

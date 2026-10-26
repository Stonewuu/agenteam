package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.integration.request.FeishuSetupRequest;
import com.stonewu.agenteam.model.integration.response.FeishuSetupView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.integration.feishu.FeishuSetupApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 飞书快捷创建的管理接口，业务步骤由后台任务推进。 */
@RestController
@RequestMapping({"/api/v1/enterprises/{enterpriseId}/chat/feishu", "/api/v1/system/enterprises/{enterpriseId}/chat/feishu"})
public class FeishuSetupApiController {
    private final FeishuSetupApiService service;
    private final ApiResponses responses;

    public FeishuSetupApiController(FeishuSetupApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<FeishuSetupApiService.Overview> overview(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(service.overview(enterpriseId, request), request);
    }

    @PostMapping("/jobs")
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId, @RequestBody FeishuSetupRequest input, HttpServletRequest request) {
        return responses.operation(service.create(enterpriseId, input, request), request);
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<FeishuSetupView> get(@PathVariable String enterpriseId, @PathVariable String id, HttpServletRequest request) {
        return responses.success(service.get(enterpriseId, id, request), request);
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<ApiResponse<Object>> retry(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody FeishuSetupApiService.Action input, HttpServletRequest request) {
        return responses.operation(service.change(enterpriseId, id, input, true, request), request);
    }

    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<ApiResponse<Object>> cancel(@PathVariable String enterpriseId, @PathVariable String id,
            @RequestBody FeishuSetupApiService.Action input, HttpServletRequest request) {
        return responses.operation(service.change(enterpriseId, id, input, false, request), request);
    }

    @GetMapping("/jobs/{id}/open")
    public ResponseEntity<Void> open(@PathVariable String enterpriseId, @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(service.open(enterpriseId, id, request)).build();
    }

    @GetMapping(value = "/jobs/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String enterpriseId, @PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(service.qr(enterpriseId, id, request));
    }

    @PostMapping("/managers/{connectionId}/authorize")
    public ResponseEntity<ApiResponse<Object>> authorize(@PathVariable String enterpriseId, @PathVariable String connectionId, HttpServletRequest request) {
        return responses.operation(service.authorize(enterpriseId, connectionId, request), request);
    }

    @PostMapping("/managers/{connectionId}/disconnect")
    public ResponseEntity<ApiResponse<Object>> disconnect(@PathVariable String enterpriseId, @PathVariable String connectionId, HttpServletRequest request) {
        return responses.operation(service.disconnect(enterpriseId, connectionId, request), request);
    }
}

package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.integration.request.IntegrationCreateRequest;
import com.stonewu.agenteam.model.integration.request.IntegrationSecretRequest;
import com.stonewu.agenteam.model.integration.request.IntegrationStatusRequest;
import com.stonewu.agenteam.model.integration.request.IntegrationUpdateRequest;
import com.stonewu.agenteam.model.integration.response.IntegrationView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.integration.IntegrationApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入管理的企业与系统入口；授权和业务处理均交给服务层。
 */
@RestController
@RequestMapping({"/api/v1/enterprises/{enterpriseId}/integrations", "/api/v1/system/enterprises/{enterpriseId}/integrations"})
public class IntegrationApiController {
    private final IntegrationApiService integrations;
    private final ApiResponses responses;

    public IntegrationApiController(IntegrationApiService integrations, ApiResponses responses) {
        this.integrations = integrations;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<PageResponse<IntegrationView>> list(@PathVariable String enterpriseId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return responses.success(integrations.list(enterpriseId, cursor, limit, request), request);
    }

    @GetMapping("/{connectionId}")
    public ApiResponse<IntegrationView> get(@PathVariable String enterpriseId, @PathVariable String connectionId,
                                           HttpServletRequest request) {
        return responses.success(integrations.get(enterpriseId, connectionId, request), request);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> create(@PathVariable String enterpriseId,
            @RequestBody IntegrationCreateRequest input, HttpServletRequest request) {
        return responses.operation(integrations.create(enterpriseId, input, request), request);
    }

    @PatchMapping("/{connectionId}")
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId, @PathVariable String connectionId,
            @RequestBody IntegrationUpdateRequest input, HttpServletRequest request) {
        return responses.operation(integrations.update(enterpriseId, connectionId, input, request), request);
    }

    @PostMapping("/{connectionId}/rotate-secret")
    public ResponseEntity<ApiResponse<Object>> rotate(@PathVariable String enterpriseId, @PathVariable String connectionId,
            @RequestBody IntegrationSecretRequest input, HttpServletRequest request) {
        return responses.operation(integrations.rotate(enterpriseId, connectionId, input, request), request);
    }

    @PostMapping("/{connectionId}/check")
    public ResponseEntity<ApiResponse<Object>> check(@PathVariable String enterpriseId, @PathVariable String connectionId,
                                                   HttpServletRequest request) {
        return responses.operation(integrations.check(enterpriseId, connectionId, request), request);
    }

    @PatchMapping("/{connectionId}/status")
    public ResponseEntity<ApiResponse<Object>> status(@PathVariable String enterpriseId, @PathVariable String connectionId,
            @RequestBody IntegrationStatusRequest input, HttpServletRequest request) {
        return responses.operation(integrations.status(enterpriseId, connectionId, input, request), request);
    }

    @DeleteMapping("/{connectionId}")
    public ResponseEntity<ApiResponse<Object>> delete(@PathVariable String enterpriseId, @PathVariable String connectionId,
                                                    HttpServletRequest request) {
        return responses.operation(integrations.delete(enterpriseId, connectionId, request), request);
    }
}

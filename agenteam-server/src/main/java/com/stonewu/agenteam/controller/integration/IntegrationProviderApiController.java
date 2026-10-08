package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.integration.response.IntegrationProviderView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.integration.IntegrationApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 接入表单读取实际注册的渠道目录，新增渠道不需要修改通用表单。 */
@RestController
@RequestMapping({"/api/v1/enterprises/{enterpriseId}/integration-providers", "/api/v1/system/enterprises/{enterpriseId}/integration-providers"})
public class IntegrationProviderApiController {
    private final IntegrationApiService integrations;
    private final ApiResponses responses;

    public IntegrationProviderApiController(IntegrationApiService integrations, ApiResponses responses) {
        this.integrations = integrations;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<IntegrationProviderView>> list(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(integrations.providers(enterpriseId, request), request);
    }
}

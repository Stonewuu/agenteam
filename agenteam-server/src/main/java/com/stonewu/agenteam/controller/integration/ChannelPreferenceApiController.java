package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.integration.request.ChannelPreferenceRequest;
import com.stonewu.agenteam.model.integration.response.ChannelPreferenceView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.integration.ChannelPreferenceApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 本企业、本用户的自动通知偏好。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/me/channel-preferences")
public class ChannelPreferenceApiController {
    private final ChannelPreferenceApiService preferences;
    private final ApiResponses responses;

    public ChannelPreferenceApiController(ChannelPreferenceApiService preferences, ApiResponses responses) {
        this.preferences = preferences;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<ChannelPreferenceView>> list(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(preferences.list(enterpriseId, request), request);
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Object>> update(@PathVariable String enterpriseId, @RequestBody ChannelPreferenceRequest input,
                                                      HttpServletRequest request) {
        return responses.operation(preferences.update(enterpriseId, input, request), request);
    }
}

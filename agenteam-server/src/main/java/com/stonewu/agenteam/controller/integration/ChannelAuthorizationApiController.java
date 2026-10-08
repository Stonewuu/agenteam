package com.stonewu.agenteam.controller.integration;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.integration.request.ChannelAuthorizationStartRequest;
import com.stonewu.agenteam.model.integration.request.ChannelAuthorizationCancelRequest;
import com.stonewu.agenteam.model.integration.response.ChannelAuthorizationReview;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.InputValidation;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 授权入口及回调，回调无论成功失败都跳转到不含授权码的本站地址。 */
@RestController
public class ChannelAuthorizationApiController {
    private final ChannelAuthorizationService authorization;
    private final ApiResponses responses;

    public ChannelAuthorizationApiController(ChannelAuthorizationService authorization, ApiResponses responses) {
        this.authorization = authorization;
        this.responses = responses;
    }

    @GetMapping("/api/v1/auth/channel-login/{key}")
    public ApiResponse<Map<String, String>> loginInfo(@PathVariable String key, HttpServletRequest request) {
        return responses.success(authorization.loginInfo(key), request);
    }

    @PostMapping("/api/v1/auth/channel-login/{key}")
    public ApiResponse<Map<String, String>> login(@PathVariable String key, @RequestBody ChannelAuthorizationStartRequest input,
                                                 HttpServletRequest request) {
        InputValidation.request(request, ChannelAuthorizationStartRequest.class);
        return responses.success(Map.of("authorizationUrl", authorization.login(key, Boolean.TRUE.equals(input.embeddedClient()), request).toString()), request);
    }

    @PostMapping("/api/v1/enterprises/{enterpriseId}/me/channels/{connectionId}/authorize")
    public ApiResponse<Map<String, String>> bind(@PathVariable String enterpriseId, @PathVariable String connectionId,
                                                @RequestBody ChannelAuthorizationStartRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChannelAuthorizationStartRequest.class);
        return responses.success(Map.of("authorizationUrl", authorization.bind(enterpriseId, connectionId, Boolean.TRUE.equals(input.embeddedClient()), request).toString()), request);
    }

    @GetMapping("/api/v1/auth/channel-callbacks/{connectionId}")
    public ResponseEntity<Void> callback(@PathVariable String connectionId, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(authorization.callback(connectionId, state, code, request)).build();
    }

    @GetMapping("/api/v1/auth/channel-authorization")
    public ApiResponse<ChannelAuthorizationReview> review(HttpServletRequest request) {
        return responses.success(authorization.review(request.getSession(false)), request);
    }

    @PostMapping("/api/v1/auth/channel-authorization/cancel")
    public ApiResponse<Map<String, Boolean>> cancel(@RequestBody ChannelAuthorizationCancelRequest input, HttpServletRequest request) {
        InputValidation.request(request, ChannelAuthorizationCancelRequest.class);
        authorization.cancel(input.authorizationId(), request.getSession(false));
        return responses.success(Map.of("cancelled", true), request);
    }
}

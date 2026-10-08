package com.stonewu.agenteam.controller.auth;

import com.stonewu.agenteam.model.auth.request.ReauthenticationRequest;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.IdentityApiService;
import com.stonewu.agenteam.service.auth.LocalAuthenticationService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 接收本地重新认证请求，验证后重新建立当前账号的会话。 */
@RestController
public class ReauthenticationApiController {
    private final LocalAuthenticationService authentication;
    private final IdentityApiService identity;
    private final ApiResponses responses;

    public ReauthenticationApiController(LocalAuthenticationService authentication, IdentityApiService identity,
                                         ApiResponses responses) {
        this.authentication = authentication;
        this.identity = identity;
        this.responses = responses;
    }

    @PostMapping("/api/v1/auth/reauthenticate")
    public ApiResponse<CurrentIdentityResponse> reauthenticate(@RequestBody ReauthenticationRequest input,
                                                               HttpServletRequest request) {
        InputValidation.request(request, ReauthenticationRequest.class);
        authentication.reauthenticate(input.password(), request);
        return responses.success(identity.current(request.getSession(false)), request);
    }
}

package com.stonewu.agenteam.controller.auth;

import com.stonewu.agenteam.model.auth.request.*;
import com.stonewu.agenteam.model.auth.response.BootstrapStatusResponse;
import com.stonewu.agenteam.model.auth.response.CurrentIdentityResponse;
import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.service.auth.AccountRecoveryService;
import com.stonewu.agenteam.service.auth.AuthService;
import com.stonewu.agenteam.service.auth.IdentityApiService;
import com.stonewu.agenteam.service.auth.RequestForgeryProtection;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 全局身份接口不借用其他标签页的当前企业。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class IdentityApiController {

    private final AuthService authentication;

    private final IdentityApiService identity;

    private final RequestForgeryProtection protection;

    private final ApiResponses responses;

    private final AccountRecoveryService recovery;

    public IdentityApiController(AuthService authentication, IdentityApiService identity,
                                 RequestForgeryProtection protection, ApiResponses responses,
                                 AccountRecoveryService recovery) {
        this.authentication = authentication;
        this.identity = identity;
        this.protection = protection;
        this.responses = responses;
        this.recovery = recovery;
    }

    @GetMapping("/bootstrap-status")
    public ApiResponse<BootstrapStatusResponse> bootstrapStatus(HttpServletRequest request) {
        return responses.success(authentication.bootstrapStatus(), request);
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(HttpServletRequest request) {
        return responses.success(Map.of("token", protection.token(request.getSession())), request);
    }

    @PostMapping("/bootstrap")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CurrentIdentityResponse> bootstrap(@RequestBody ApiBootstrapRequest payload,
                                                          HttpServletRequest request) {
        return responses.success(identity.bootstrap(payload, request), request);
    }

    @GetMapping("/me")
    public ApiResponse<CurrentIdentityResponse> me(HttpServletRequest request) {
        return responses.success(identity.current(request.getSession(false)), request);
    }

    @PostMapping("/login")
    public ApiResponse<CurrentIdentityResponse> login(@RequestBody ApiLoginRequest payload,
                                                      HttpServletRequest request) {
        return responses.success(identity.login(payload, request), request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
    }

    @PostMapping("/password-reset-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Boolean>> requestReset(@RequestBody PasswordResetRequest payload,
                                                          HttpServletRequest request) {
        recovery.requestPasswordReset(payload, request.getRemoteAddr());
        return responses.success(Map.of("success", true), request);
    }

    @PostMapping("/password-reset")
    public ApiResponse<Map<String, Boolean>> reset(@RequestBody PasswordResetConfirmRequest payload,
                                                   HttpServletRequest request) {
        identity.resetPassword(payload, request);
        return responses.success(Map.of("success", true), request);
    }

    @PostMapping("/email-verify")
    public ApiResponse<Map<String, Boolean>> verifyEmail(@RequestBody TokenRequest payload,
                                                         HttpServletRequest request) {
        recovery.verifyEmail(payload);
        return responses.success(Map.of("success", true), request);
    }
}

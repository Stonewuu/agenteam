package com.stonewu.agenteam.controller.user;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.user.request.EmailChangeRequest;
import com.stonewu.agenteam.model.user.request.PasswordChangeRequest;
import com.stonewu.agenteam.model.user.request.PreferenceUpdateRequest;
import com.stonewu.agenteam.model.user.request.ProfileUpdateRequest;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.user.UserSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 个人设置的请求参数与响应适配。
 */
@RestController
@RequestMapping("/api/v1/me")
public class UserProfileApiController {

    private final UserSettingsService settings;

    private final ApiResponses responses;

    public UserProfileApiController(UserSettingsService settings, ApiResponses responses) {
        this.settings = settings;
        this.responses = responses;
    }

    @PatchMapping("/profile")
    public ResponseEntity<ApiResponse<Object>> profile(@RequestBody ProfileUpdateRequest payload,
                                                       HttpServletRequest request) {
        return responses.operation(settings.profile(payload, request), request);
    }

    @PatchMapping("/preferences")
    public ResponseEntity<ApiResponse<Object>> preferences(@RequestBody PreferenceUpdateRequest payload,
                                                           HttpServletRequest request) {
        return responses.operation(settings.preferences(payload, request), request);
    }

    @PostMapping("/password")
    public ResponseEntity<ApiResponse<Object>> password(@RequestBody PasswordChangeRequest payload,
                                                        HttpServletRequest request) {
        return responses.operation(settings.password(payload, request), request);
    }

    @PostMapping("/email-change")
    public ResponseEntity<ApiResponse<Object>> email(@RequestBody EmailChangeRequest payload,
                                                     HttpServletRequest request) {
        return responses.operation(settings.email(payload, request), request);
    }
}

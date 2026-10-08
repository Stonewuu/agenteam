package com.stonewu.agenteam.controller.notification;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.notification.request.ReadNotificationCenterRequest;
import com.stonewu.agenteam.model.notification.response.NotificationCenterView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.notification.NotificationApiService;
import com.stonewu.agenteam.service.notification.NotificationCenterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 通知铃铛的汇总和一键已读入口。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/notifications/center")
public class NotificationCenterController {

    private final AuthContextService identity;

    private final NotificationCenterService center;

    private final NotificationApiService mutations;

    private final ApiResponses responses;

    public NotificationCenterController(AuthContextService identity, NotificationCenterService center,
                                        NotificationApiService mutations, ApiResponses responses) {
        this.identity = identity;
        this.center = center;
        this.mutations = mutations;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<NotificationCenterView> summary(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(center.summary(identity.requireEnterprise(request.getSession(false), enterpriseId)),
            request);
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> readAll(@PathVariable String enterpriseId,
                                     @RequestBody ReadNotificationCenterRequest input, HttpServletRequest request) {
        return responses.operation(mutations.readCenter(enterpriseId, input, request), request);
    }
}

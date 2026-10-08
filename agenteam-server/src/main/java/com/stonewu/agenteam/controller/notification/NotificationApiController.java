package com.stonewu.agenteam.controller.notification;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.notification.response.ChannelDeliveryView;
import com.stonewu.agenteam.model.notification.response.NotificationView;
import com.stonewu.agenteam.model.notification.request.ReadAllNotificationsRequest;
import com.stonewu.agenteam.model.notification.response.NotificationPageView;
import com.stonewu.agenteam.model.notification.response.UnreadCountView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.notification.NotificationApiService;
import com.stonewu.agenteam.service.notification.NotificationService;
import com.stonewu.agenteam.service.notification.ChannelDeliveryApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 通知读取和标记只面向当前已验证的企业成员本人。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/notifications")
public class NotificationApiController {

    private final AuthContextService identity;

    private final NotificationService notifications;

    private final NotificationApiService mutations;

    private final ApiResponses responses;
    private final ChannelDeliveryApiService channels;

    public NotificationApiController(AuthContextService identity, NotificationService notifications,
                                     NotificationApiService mutations, ApiResponses responses, ChannelDeliveryApiService channels) {
        this.identity = identity;
        this.notifications = notifications;
        this.mutations = mutations;
        this.responses = responses;
        this.channels = channels;
    }

    @GetMapping
    public ApiResponse<NotificationPageView> list(@PathVariable String enterpriseId,
                                                  @RequestParam(defaultValue = "false") boolean unread,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit,
                                                  HttpServletRequest request) {
        return responses.success(
            notifications.list(identity.requireEnterprise(request.getSession(false), enterpriseId), unread, cursor,
                limit), request);
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountView> count(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(
            notifications.unread(identity.requireEnterprise(request.getSession(false), enterpriseId)), request);
    }

    @GetMapping("/{notificationId}")
    public ApiResponse<NotificationView> get(@PathVariable String enterpriseId, @PathVariable String notificationId,
                                              HttpServletRequest request) {
        return responses.success(notifications.get(identity.requireEnterprise(request.getSession(false), enterpriseId), notificationId), request);
    }

    @GetMapping("/{notificationId}/deliveries")
    public ApiResponse<PageResponse<ChannelDeliveryView>> deliveries(@PathVariable String enterpriseId, @PathVariable String notificationId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(channels.forNotification(enterpriseId, notificationId, cursor, limit, request), request);
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<?> read(@PathVariable String enterpriseId, @PathVariable String notificationId,
                                  HttpServletRequest request) {
        return responses.operation(mutations.read(enterpriseId, notificationId, null, request), request);
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> readAll(@PathVariable String enterpriseId, @RequestBody ReadAllNotificationsRequest input,
                                     HttpServletRequest request) {
        return responses.operation(mutations.read(enterpriseId, null, input, request), request);
    }
}

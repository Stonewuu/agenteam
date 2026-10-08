package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.notification.request.ReadAllNotificationsRequest;
import com.stonewu.agenteam.model.notification.request.ReadNotificationCenterRequest;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.http.InputValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class NotificationApiService {
    private final AuthContextService identity;
    private final NotificationService notifications;
    private final IdempotentRequestService requests;
    private final NotificationCenterService center;

    public NotificationApiService(AuthContextService identity, NotificationService notifications,
                                  IdempotentRequestService requests, NotificationCenterService center) {
        this.identity = identity;
        this.notifications = notifications;
        this.requests = requests;
        this.center = center;
    }

    public ApiOperationResult read(String enterprise, String id, ReadAllNotificationsRequest input,
                                   HttpServletRequest request) {
        if (id == null) {
            InputValidation.request(request, ReadAllNotificationsRequest.class);
        }
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(), () -> {
            var current = identity.requireEnterprise(request.getSession(false), enterprise);
            notifications.authorize(current);
            if (id != null) {
                notifications.require(current, id);
            }
        }, () -> ApiOperationResult.of(200,
            id == null ? notifications.readAll(actor, input.throughSequence()) : notifications.read(actor, id)));
    }

    public ApiOperationResult readCenter(String enterprise, ReadNotificationCenterRequest input,
                                         HttpServletRequest request) {
        InputValidation.request(request, ReadNotificationCenterRequest.class);
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        return requests.execute(request, actor.user(), enterprise, Set.of(),
            () -> notifications.authorize(identity.requireEnterprise(request.getSession(false), enterprise)),
            () -> ApiOperationResult.of(200, center.readAll(actor, input)));
    }
}

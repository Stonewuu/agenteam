package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 企业运维只能读发送元数据；正文仍只能由通知接收人读取。 */
@Service
public class ChannelDeliveryAccess {
    private final AuthMapper users;
    private final PermissionMapper permissions;
    private final ChannelDeliveryPolicy policy;

    public ChannelDeliveryAccess(AuthMapper users, PermissionMapper permissions, ChannelDeliveryPolicy policy) {
        this.users = users;
        this.permissions = permissions;
        this.policy = policy;
    }

    public boolean operator(UserEntity actor, String enterprise, boolean system, String permission) {
        var current = users.findById(actor.id()).filter(value -> "active".equals(value.status())
            && value.sessionVersion() == actor.sessionVersion()).orElse(null);
        if (current == null) {
            return false;
        }
        return system ? current.superAdmin() : users.isActiveMember(actor.id(), enterprise)
            && permissions.operationScope(actor.id(), enterprise, permission).filter(DataScope.ENTERPRISE::equals).isPresent();
    }

    public void requireOperator(UserEntity actor, String enterprise, boolean system, String permission) {
        if (!operator(actor, enterprise, system, permission)) {
            throw unavailable();
        }
    }

    public boolean originalSender(UserEntity actor, NotificationRow notice, String reason) {
        return actor.id().equals(notice.getInitiatorUserId())
            && policy.senderAllowed(notice.getEnterpriseId(), actor.id(), notice.getUserId(), reason);
    }

    public void requireView(UserEntity actor, NotificationRow notice, String reason, boolean system) {
        if (system) {
            requireOperator(actor, notice.getEnterpriseId(), true, "notification.delivery.view");
        } else if (!notice.getUserId().equals(actor.id()) && !originalSender(actor, notice, reason)
            && !("scheduled".equals(reason) && actor.id().equals(notice.getInitiatorUserId())
                && users.isActiveMember(actor.id(), notice.getEnterpriseId())
                && permissions.operationScope(actor.id(), notice.getEnterpriseId(), "schedule.view").isPresent())
            && !operator(actor, notice.getEnterpriseId(), false, "notification.delivery.view")) {
            throw unavailable();
        }
    }

    public boolean retry(UserEntity actor, NotificationRow notice, String reason, boolean system) {
        if (system) {
            return operator(actor, notice.getEnterpriseId(), true, "notification.delivery.retry");
        }
        return originalSender(actor, notice, reason) || operator(actor, notice.getEnterpriseId(), false, "notification.delivery.retry");
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_DELIVERY_UNAVAILABLE", "发送记录不存在或无法访问。");
    }
}

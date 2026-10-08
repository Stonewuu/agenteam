package com.stonewu.agenteam.model.notification.entity;

import java.time.Instant;

/** 已写入站内通知后，同一次定时操作需要准备的一个外部渠道。 */
public record ScheduledChannelNotification(NotificationRow notification, ChannelDeliveryTarget target, Instant expiresAt) {
    @Override
    public String toString() {
        return "ScheduledChannelNotification[notificationId=" + notification.getId() + "]";
    }
}

package com.stonewu.agenteam.model.schedule.entity;

import com.stonewu.agenteam.model.notification.entity.ChannelDeliveryTarget;

import java.util.List;

/** 本次通知的固定内容、明确接收人及当时授权的渠道身份，不包含应用凭据。 */
public record NotificationScheduleSnapshot(String title, String body, List<Recipient> recipients) {
    public record Recipient(String userId, List<ChannelDeliveryTarget> channels) {
    }
}

package com.stonewu.agenteam.model.notification.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 一键已读分别限制公告和通知的已取得序号，不标记后来产生的新内容。
 */
public record ReadNotificationCenterRequest(
    @NotNull(message = "{error.notification.readNotificationCenterRequest.notificationThroughSequence.1}") @Pattern(regexp = "[0-9]{1,19}", message = "{error.notification.readNotificationCenterRequest.notificationThroughSequence.1}")
    @DecimalMax(value = "9223372036854775807", message = "{error.notification.readNotificationCenterRequest.notificationThroughSequence.1}") String notificationThroughSequence,
    @NotNull(message = "{error.notification.readNotificationCenterRequest.announcementThroughSequence.1}") @Pattern(regexp = "[0-9]{1,19}", message = "{error.notification.readNotificationCenterRequest.announcementThroughSequence.1}")
    @DecimalMax(value = "9223372036854775807", message = "{error.notification.readNotificationCenterRequest.announcementThroughSequence.1}") String announcementThroughSequence) {
}

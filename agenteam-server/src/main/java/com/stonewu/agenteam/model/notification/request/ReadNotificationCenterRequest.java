package com.stonewu.agenteam.model.notification.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 一键已读分别限制公告和通知的已取得序号，不标记后来产生的新内容。
 */
public record ReadNotificationCenterRequest(
    @NotNull(message = "请重新读取通知。") @Pattern(regexp = "[0-9]{1,19}", message = "请重新读取通知。")
    @DecimalMax(value = "9223372036854775807", message = "请重新读取通知。") String notificationThroughSequence,
    @NotNull(message = "请重新读取公告。") @Pattern(regexp = "[0-9]{1,19}", message = "请重新读取公告。")
    @DecimalMax(value = "9223372036854775807", message = "请重新读取公告。") String announcementThroughSequence) {
}

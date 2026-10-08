package com.stonewu.agenteam.model.announcement.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 批量已读只覆盖本次已取得的公告范围。
 */
public record AnnouncementReadAllRequest(@NotNull(message = "请重新读取公告列表。")
                                         @DecimalMax(value = "9223372036854775807", message = "请重新读取列表。") @Pattern(regexp = "[0-9]{1,19}", message = "请重新读取公告列表。") String throughSequence) {
}

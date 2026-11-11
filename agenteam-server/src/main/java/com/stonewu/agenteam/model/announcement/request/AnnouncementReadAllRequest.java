package com.stonewu.agenteam.model.announcement.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 批量已读只覆盖本次已取得的公告范围。
 */
public record AnnouncementReadAllRequest(@NotNull(message = "{error.announcement.announcementReadAllRequest.throughSequence.1}")
                                         @DecimalMax(value = "9223372036854775807", message = "{error.announcement.announcementReadAllRequest.throughSequence.2}") @Pattern(regexp = "[0-9]{1,19}", message = "{error.announcement.announcementReadAllRequest.throughSequence.1}") String throughSequence) {
}

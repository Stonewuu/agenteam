package com.stonewu.agenteam.model.announcement.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 仅标记用户实际读到的内容版本。
 */
public record AnnouncementReadRequest(@NotNull(message = "请重新打开公告。")
                                      @DecimalMax(value = "9223372036854775807", message = "请重新读取列表。") @Pattern(regexp = "[1-9][0-9]{0,18}", message = "请重新打开公告。") String version) {
}

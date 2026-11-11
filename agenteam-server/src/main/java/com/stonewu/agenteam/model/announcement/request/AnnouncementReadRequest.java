package com.stonewu.agenteam.model.announcement.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 仅标记用户实际读到的内容版本。
 */
public record AnnouncementReadRequest(@NotNull(message = "{error.announcement.announcementReadRequest.version.1}")
                                      @DecimalMax(value = "9223372036854775807", message = "{error.announcement.announcementReadAllRequest.throughSequence.2}") @Pattern(regexp = "[1-9][0-9]{0,18}", message = "{error.announcement.announcementReadRequest.version.1}") String version) {
}

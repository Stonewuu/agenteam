package com.stonewu.agenteam.model.announcement.request;

import jakarta.validation.constraints.NotNull;

/**
 * 启用当前公告内容，或停止向用户展示。
 */
public record AnnouncementStatusRequest(@NotNull(message = "{error.announcement.announcementStatusRequest.enabled.1}") Boolean enabled) {
}

package com.stonewu.agenteam.model.announcement.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存公告标题、正文和提醒等级，范围由请求入口确定。
 */
public record AnnouncementWriteRequest(
    @NotBlank(message = "{error.announcement.announcementWriteRequest.title.1}") @Size(max = 160, message = "{error.announcement.announcementWriteRequest.title.2}") String title,
    @NotBlank(message = "{error.announcement.announcementWriteRequest.content.1}") @Size(max = 200000, message = "{error.announcement.announcementWriteRequest.content.2}") String content,
    @NotBlank(message = "{error.announcement.announcementWriteRequest.level.1}") @Size(max = 32, message = "{error.announcement.announcementWriteRequest.level.2}") String level,
    @Size(max = 20, message = "{error.announcement.announcementWriteRequest.contentFormat.1}") String contentFormat) {
}

package com.stonewu.agenteam.model.announcement.response;

/**
 * 公告配置与当前用户的已读状态。
 */
public record AnnouncementView(String id, String scope, String enterpriseId, String title, String content,
                               String contentFormat,
                               AnnouncementLevel level, boolean enabled, String version, String revision,
                               String publicationSequence,
                               String publishedAt, String publisherName, String createdAt, String updatedAt,
                               String readAt) {
}

package com.stonewu.agenteam.model.notification.response;

import com.stonewu.agenteam.model.announcement.response.AnnouncementUnreadView;

/**
 * 铃铛同时展示公告与通知的真实未读状态。
 */
public record NotificationCenterView(UnreadCountView notifications, AnnouncementUnreadView announcements) {
}

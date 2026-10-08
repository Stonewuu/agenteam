package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.notification.request.ReadNotificationCenterRequest;
import com.stonewu.agenteam.model.notification.response.NotificationCenterView;
import com.stonewu.agenteam.service.announcement.AnnouncementUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公告和通知的一键已读共同提交，任何一项失败都保留原来的未读状态。
 */
@Service
public class NotificationCenterService {
    private final NotificationService notifications;
    private final AnnouncementUserService announcements;

    public NotificationCenterService(NotificationService notifications, AnnouncementUserService announcements) {
        this.notifications = notifications;
        this.announcements = announcements;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationCenterView summary(AuthContext actor) {
        return new NotificationCenterView(notifications.unread(actor), announcements.unread(actor));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public NotificationCenterView readAll(AuthContext actor, ReadNotificationCenterRequest input) {
        notifications.readAll(actor, input.notificationThroughSequence());
        announcements.readAll(actor, Long.parseLong(input.announcementThroughSequence()));
        return summary(actor);
    }
}

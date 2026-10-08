package com.stonewu.agenteam.mapper.notification;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 只处理指定本人执行的完成通知，不影响失败、审批和其他对话提醒。
 */
@Repository
public class ExecutionResultNoticeMapper {
    private final NotificationDeliverySqlMapper jobs;
    private final NotificationSqlMapper notifications;

    public ExecutionResultNoticeMapper(NotificationDeliverySqlMapper jobs, NotificationSqlMapper notifications) {
        this.jobs = jobs;
        this.notifications = notifications;
    }

    public void viewed(String enterprise, String user, String run, String conversation, Instant now) {
        String event = "run:" + run + ":completed";
        jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>()
            .eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getOwnerUserId, user)
            .eq(BackgroundJobRow::getKind, "notification")
            .eq(BackgroundJobRow::getDedupeKey, "notification:" + enterprise + ":" + user + ":" + event)
            .eq(BackgroundJobRow::getStatus, "queued")
            .set(BackgroundJobRow::getStatus, "cancelled")
            .set(BackgroundJobRow::getUpdatedAt, now));
        // 网络较慢或稍后返回对话时，通知可能已经生成，此时只标记对应的一条已读。
        notifications.update(new LambdaUpdateWrapper<NotificationRow>()
            .eq(NotificationRow::getEnterpriseId, enterprise)
            .eq(NotificationRow::getUserId, user)
            .eq(NotificationRow::getEventKey, event)
            .eq(NotificationRow::getCategory, "execution")
            .eq(NotificationRow::getTargetType, "conversation")
            .eq(NotificationRow::getTargetId, conversation)
            .isNull(NotificationRow::getReadAt)
            .set(NotificationRow::getReadAt, now));
    }
}

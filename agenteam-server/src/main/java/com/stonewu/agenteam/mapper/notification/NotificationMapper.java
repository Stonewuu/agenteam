package com.stonewu.agenteam.mapper.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.notification.entity.NotificationQueryRow;
import com.stonewu.agenteam.model.notification.response.NotificationView;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 每个查询都限定企业和接收成员，管理员身份也不扩大通知读取范围。
 */
@Repository
public class NotificationMapper {
    private final NotificationSqlMapper statements;
    private final IdentityQueryMapper members;

    public NotificationMapper(NotificationSqlMapper statements, IdentityQueryMapper members) {
        this.statements = statements;
        this.members = members;
    }

    public Optional<Long> sequence(String enterprise, String user, boolean lock) {
        if (lock) {
            return statements.lockSequence(enterprise, user).stream().findFirst();
        }
        return members.selectList(new LambdaQueryWrapper<EnterpriseMemberRow>()
                .select(EnterpriseMemberRow::getNotificationSequence)
                .eq(EnterpriseMemberRow::getEnterpriseId, enterprise)
                .eq(EnterpriseMemberRow::getUserId, user)
                .eq(EnterpriseMemberRow::getStatus, "active"))
            .stream().map(EnterpriseMemberRow::getNotificationSequence).findFirst();
    }

    public long unread(String enterprise, String user) {
        return DataAccessUtils.nullableSingleResult(statements.unreadNotification(enterprise, user));
    }

    public Optional<NotificationView> find(String enterprise, String user, String id, boolean lock) {
        return statements.findNotification(enterprise, user, id, lock).stream().map(this::map).findFirst();
    }

    public List<NotificationView> page(String enterprise, String user, boolean unread, long through, Long before,
                                       int limit) {
        return statements.pageNotifications(enterprise, user, unread, through, before, limit + 1).stream()
            .map(this::map).toList();
    }

    public void read(String enterprise, String user, String id, Instant now) {
        statements.readNotification(timestamp(now), enterprise, user, id);
    }

    public void readThrough(String enterprise, String user, long through, Instant now) {
        statements.readThroughNotification(timestamp(now), enterprise, user, through);
    }

    private NotificationView map(NotificationQueryRow row) {
        var read = instant(row.getReadAt());
        return new NotificationView(row.getId(), Long.toString(row.getSequenceNo()), row.getCategory(), row.getTitle(),
            row.getBody(), row.getTargetType(), row.getTargetId(), read == null ? null : read.toString(),
            instant(row.getCreatedAt()).toString());
    }
}

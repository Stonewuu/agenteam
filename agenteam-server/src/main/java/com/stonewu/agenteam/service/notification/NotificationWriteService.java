package com.stonewu.agenteam.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.mapper.notification.NotificationSqlMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** 站内通知统一按企业、成员顺序加锁，分配序号并按业务事件防止重复创建。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationWriteService {
    private final EnterpriseMapper enterprises;
    private final AuthMapper users;
    private final IdentityQueryMapper members;
    private final NotificationSqlMapper notifications;
    private final Clock clock;

    public NotificationWriteService(EnterpriseMapper enterprises, AuthMapper users, IdentityQueryMapper members,
                                    NotificationSqlMapper notifications, Clock clock) {
        this.enterprises = enterprises;
        this.users = users;
        this.members = members;
        this.notifications = notifications;
        this.clock = clock;
    }

    public Optional<Written> write(String enterprise, String user, Notice notice, String occurrence, String initiator) {
        return lockRecipient(enterprise, user).map(recipient -> writeLocked(recipient, notice, occurrence, initiator));
    }

    public Optional<Recipient> lockRecipient(String enterprise, String user) {
        boolean activeEnterprise = enterprises.lockEnterprise(enterprise).filter("active"::equals).isPresent();
        if (!activeEnterprise) {
            return Optional.empty();
        }
        var sequence = notifications.lockSequence(enterprise, user);
        if (sequence.isEmpty() || !users.isActiveMember(user, enterprise)) {
            return Optional.empty();
        }
        return Optional.of(new Recipient(enterprise, user, sequence.getFirst()));
    }

    // 旧通知工作在取得成员锁后才领取工作行；接收人只在当前事务内使用。
    Written writeLocked(Recipient recipient, Notice notice, String occurrence, String initiator) {
        var existing = notifications.selectOne(new LambdaQueryWrapper<NotificationRow>()
            .eq(NotificationRow::getEnterpriseId, recipient.enterprise()).eq(NotificationRow::getUserId, recipient.user())
            .eq(NotificationRow::getEventKey, notice.eventKey()));
        if (existing != null) {
            return new Written(existing, false);
        }
        long sequence = Math.addExact(recipient.sequence(), 1);
        if (members.updateNotificationSequence(sequence, recipient.enterprise(), recipient.user()) != 1) {
            throw new IllegalStateException("通知接收成员的序号无法更新");
        }
        var row = new NotificationRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(recipient.enterprise());
        row.setUserId(recipient.user());
        row.setSequenceNo(sequence);
        row.setEventKey(notice.eventKey());
        row.setCategory(notice.category());
        row.setTitle(notice.title());
        row.setBody(notice.body());
        row.setTargetType(notice.targetType());
        row.setTargetId(notice.targetId());
        row.setSourceOccurrenceId(occurrence);
        row.setInitiatorUserId(initiator);
        row.setCreatedAt(clock.instant());
        notifications.insert(row);
        return new Written(row, true);
    }

    public record Recipient(String enterprise, String user, long sequence) {
    }

    public record Written(NotificationRow notification, boolean created) {
    }
}

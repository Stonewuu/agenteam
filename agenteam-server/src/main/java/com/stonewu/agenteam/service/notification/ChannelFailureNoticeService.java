package com.stonewu.agenteam.service.notification;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper;
import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.mapper.notification.NotificationRecipientMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

/** 发送失败只创建站内运维提醒，类别不参与外部自动转发，防止失败通知继续触发失败。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChannelFailureNoticeService {
    private final NotificationRecipientMapper recipients;
    private final NotificationDeliveryMapper notifications;
    private final EnterpriseMapper enterprises;
    private final Clock clock;

    public ChannelFailureNoticeService(NotificationRecipientMapper recipients, NotificationDeliveryMapper notifications,
                                        EnterpriseMapper enterprises, Clock clock) {
        this.recipients = recipients;
        this.notifications = notifications;
        this.enterprises = enterprises;
        this.clock = clock;
    }

    public void enqueue(NotificationChannelDeliveryRow row) {
        if (!Set.of("failed", "unknown").contains(row.getStatus())
            || enterprises.findById(row.getEnterpriseId()).filter(value -> "active".equals(value.status())).isEmpty()) {
            return;
        }
        var notice = new Notice("channel-failure:" + row.getId(), "integration", Messages.text("error.notification.channelFailureNoticeService.enqueue.1"),
            "unknown".equals(row.getStatus()) ? Messages.text("error.notification.channelFailureNoticeService.enqueue.2") : Messages.text("error.notification.channelFailureNoticeService.enqueue.3"),
            "notification_delivery", row.getId());
        for (String user : recipients.administrators(row.getEnterpriseId())) {
            notifications.enqueue(row.getEnterpriseId(), user, notice, clock.instant());
        }
    }
}

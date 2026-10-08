package com.stonewu.agenteam.service.notification;

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
        var notice = new Notice("channel-failure:" + row.getId(), "integration", "有一条渠道通知未能完成",
            "unknown".equals(row.getStatus()) ? "平台是否已接受该通知暂时无法确认，请查看发送记录后处理。" : "通知未能通过外部应用发送，请查看发送记录中的原因。",
            "notification_delivery", row.getId());
        for (String user : recipients.administrators(row.getEnterpriseId())) {
            notifications.enqueue(row.getEnterpriseId(), user, notice, clock.instant());
        }
    }
}

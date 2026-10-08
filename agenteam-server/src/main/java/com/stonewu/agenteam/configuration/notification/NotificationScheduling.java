package com.stonewu.agenteam.configuration.notification;

import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 提醒写入失败保留队列，下次仍使用相同业务事件编号。
 */
@Configuration
@ConditionalOnProperty(name = "agenteam.maintenance.worker-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduling {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationScheduling.class);

    private final NotificationDeliveryService notifications;

    public NotificationScheduling(NotificationDeliveryService notifications) {
        this.notifications = notifications;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void poll() {
        try {
            for (var candidate : notifications.candidates()) {
                try {
                    notifications.deliver(candidate);
                } catch (RuntimeException failure) {
                    LOG.warn("通知暂未保存，下次继续处理：工作编号={}", candidate.id(), failure);
                }
            }
        } catch (RuntimeException failure) {
            LOG.warn("通知队列暂时无法读取", failure);
        }
    }
}

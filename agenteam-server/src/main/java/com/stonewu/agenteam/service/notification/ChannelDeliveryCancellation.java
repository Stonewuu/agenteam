package com.stonewu.agenteam.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.notification.NotificationChannelDeliveryMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 持有企业锁后批量停止尚未开始的发送；正在网络中执行的请求保留真实返回结果。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChannelDeliveryCancellation {
    private final NotificationChannelDeliveryMapper deliveries;
    private final Clock clock;

    public ChannelDeliveryCancellation(NotificationChannelDeliveryMapper deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public void cancel(String enterprise, String connection, String binding, String user, String category) {
        deliveries.cancelWaiting(enterprise, connection, binding, user, category, null, clock.instant());
        deliveries.cancelStoppedJobs(enterprise, clock.instant());
    }

    public void occurrence(String enterprise, String occurrence) {
        deliveries.cancelWaiting(enterprise, null, null, null, null, occurrence, clock.instant());
        deliveries.cancelStoppedJobs(enterprise, clock.instant());
    }

    public void requireNoActive(String enterprise, String connection) {
        if (deliveries.exists(new LambdaQueryWrapper<NotificationChannelDeliveryRow>()
            .eq(NotificationChannelDeliveryRow::getEnterpriseId, enterprise).eq(NotificationChannelDeliveryRow::getConnectionId, connection)
            .in(NotificationChannelDeliveryRow::getStatus, ChannelDeliveryStore.ACTIVE))) {
            throw new ApiException(HttpStatus.CONFLICT, "INTEGRATION_DELIVERY_ACTIVE", "此接入仍有正在处理的通知，请稍后再删除。");
        }
    }
}

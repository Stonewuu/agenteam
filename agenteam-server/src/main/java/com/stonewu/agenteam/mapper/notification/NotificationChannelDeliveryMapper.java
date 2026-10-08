package com.stonewu.agenteam.mapper.notification;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationChannelDeliveryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/** 通知经一个外部应用发给一个成员的持久记录的数据库访问。 */
@Mapper
public interface NotificationChannelDeliveryMapper extends MPJBaseMapper<NotificationChannelDeliveryRow> {
    NotificationChannelDeliveryRow lock(@Param("enterprise") String enterprise, @Param("id") String id);

    int cancelWaiting(@Param("enterprise") String enterprise, @Param("connection") String connection,
                      @Param("binding") String binding, @Param("user") String user, @Param("category") String category,
                      @Param("occurrence") String occurrence, @Param("now") Instant now);

    int cancelStoppedJobs(@Param("enterprise") String enterprise, @Param("now") Instant now);
}

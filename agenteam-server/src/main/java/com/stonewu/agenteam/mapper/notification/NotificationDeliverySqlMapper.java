package com.stonewu.agenteam.mapper.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.notification.entity.NotificationDeliveryQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * NotificationDeliveryMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface NotificationDeliverySqlMapper extends MPJBaseMapper<BackgroundJobRow> {
    int enqueueBackgroundJob(@Param("value") String value, @Param("enterprise") String enterprise,
                             @Param("user") String user, @Param("value2") String value2, @Param("value3") String value3,
                             @Param("now") Timestamp now, @Param("availableAt") Timestamp availableAt);

    default List<NotificationDeliveryQueryRow> candidatesBackgroundJob(Timestamp now) {
        var criteria = new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId,
                BackgroundJobRow::getEnterpriseId, BackgroundJobRow::getOwnerUserId)
            .orderByAsc(BackgroundJobRow::getAvailableAt).orderByAsc(BackgroundJobRow::getId)
            .eq(BackgroundJobRow::getKind, "notification").eq(BackgroundJobRow::getStatus, "queued")
            .le(BackgroundJobRow::getAvailableAt, now);
        long pageSize = 100;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<BackgroundJobRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new NotificationDeliveryQueryRow();
                if (storedRow.getId() != null) {
                    mappedRow.setId(storedRow.getId());
                }
                if (storedRow.getEnterpriseId() != null) {
                    mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
                }
                if (storedRow.getOwnerUserId() != null) {
                    mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
                }
                return mappedRow;
            }).toList();
    }

    List<BackgroundJobRow> deliverBackgroundJob(@Param("id") String id, @Param("enterprise") String enterprise,
                                      @Param("user") String user);


    default int finishNotificationJob(String status, Timestamp now, String id, String enterprise, String user) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getEnterpriseId, enterprise).eq(BackgroundJobRow::getOwnerUserId, user)
            .eq(BackgroundJobRow::getKind, "notification").eq(BackgroundJobRow::getStatus, "queued")
            .set(BackgroundJobRow::getStatus, status).set(BackgroundJobRow::getUpdatedAt, now));
    }


}

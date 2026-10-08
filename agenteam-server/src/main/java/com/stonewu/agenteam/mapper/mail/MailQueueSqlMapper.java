package com.stonewu.agenteam.mapper.mail;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.mail.entity.MailQueueQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * MailQueueMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface MailQueueSqlMapper extends MPJBaseMapper<BackgroundJobRow> {
    List<MailQueueQueryRow> latestInvitationBackgroundJob(@Param("enterpriseId") String enterpriseId,
                                                          @Param("value") String value);

    List<MailQueueQueryRow> invitationRetryAfterBackgroundJob(@Param("enterpriseId") String enterpriseId,
                                                              @Param("value") String value,
                                                              @Param("time") Timestamp time);

    default int replacePayloadBackgroundJob(String ownerUserId, String payload, Timestamp now, String id,
                                            String leaseOwner, long leaseVersion) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getStatus, "leased").eq(BackgroundJobRow::getLeaseOwner, leaseOwner)
            .eq(BackgroundJobRow::getLeaseVersion, leaseVersion).gt(BackgroundJobRow::getLeaseUntil, now)
            .set(BackgroundJobRow::getOwnerUserId, ownerUserId).set(BackgroundJobRow::getPayloadJson, payload)
            .set(BackgroundJobRow::getUpdatedAt, now));
    }
}

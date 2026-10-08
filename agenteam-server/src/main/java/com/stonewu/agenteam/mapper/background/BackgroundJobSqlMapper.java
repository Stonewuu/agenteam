package com.stonewu.agenteam.mapper.background;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobQueryRow;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * BackgroundJobMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface BackgroundJobSqlMapper extends MPJBaseMapper<BackgroundJobRow> {
    default int enqueueBackgroundJob(String id, String enterpriseId, String userId, String kind, String dedupeKey,
                                     String payload, Timestamp now) {
        var databaseRow = new BackgroundJobRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setOwnerUserId(userId);
        databaseRow.setKind(kind);
        databaseRow.setDedupeKey(dedupeKey);
        databaseRow.setPayloadJson(payload);
        databaseRow.setAvailableAt((now == null ? null : now.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    List<String> claimFileWorkEnterprise(@Param("kind") String kind, @Param("now") Timestamp now);

    List<BackgroundJobQueryRow> claimBackgroundJob(@Param("kind") String kind, @Param("now") Timestamp now,
                                                   @Param("enterprise") String enterprise);

    default int assignLease(String workerId, long leaseVersion, Timestamp leaseUntil, Timestamp now, int attempts,
                            String id) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .set(BackgroundJobRow::getStatus, "leased").set(BackgroundJobRow::getLeaseOwner, workerId)
            .set(BackgroundJobRow::getLeaseVersion, leaseVersion).set(BackgroundJobRow::getLeaseUntil, leaseUntil)
            .set(BackgroundJobRow::getHeartbeatAt, now).set(BackgroundJobRow::getAttemptCount, attempts)
            .set(BackgroundJobRow::getUpdatedAt, now));
    }

    default int setAttemptLimitBackgroundJob(int maximum, String id) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getStatus, "queued").set(BackgroundJobRow::getMaxAttempts, maximum));
    }

    default int renewBackgroundJob(Timestamp leaseUntil, Timestamp now, String id, String leaseOwner,
                                   long leaseVersion) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getStatus, "leased").eq(BackgroundJobRow::getLeaseOwner, leaseOwner)
            .eq(BackgroundJobRow::getLeaseVersion, leaseVersion).gt(BackgroundJobRow::getLeaseUntil, now)
            .set(BackgroundJobRow::getLeaseUntil, leaseUntil).set(BackgroundJobRow::getHeartbeatAt, now)
            .set(BackgroundJobRow::getUpdatedAt, now));
    }

    List<String> lockOwnedBackgroundJob(@Param("id") String id, @Param("leaseOwner") String leaseOwner,
                                        @Param("leaseVersion") long leaseVersion, @Param("now") Timestamp now);

    default int finishBackgroundJob(String status, String errorCode, String summary, Timestamp availableAt,
                                    Timestamp now, String id, String leaseOwner, long leaseVersion) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getStatus, "leased").eq(BackgroundJobRow::getLeaseOwner, leaseOwner)
            .eq(BackgroundJobRow::getLeaseVersion, leaseVersion).gt(BackgroundJobRow::getLeaseUntil, now)
            .set(BackgroundJobRow::getStatus, status).set(BackgroundJobRow::getErrorCode, errorCode)
            .set(BackgroundJobRow::getErrorSummary, summary).set(BackgroundJobRow::getAvailableAt, availableAt)
            .set(BackgroundJobRow::getLeaseOwner, null).set(BackgroundJobRow::getLeaseUntil, null)
            .set(BackgroundJobRow::getHeartbeatAt, null).set(BackgroundJobRow::getUpdatedAt, now));
    }
}

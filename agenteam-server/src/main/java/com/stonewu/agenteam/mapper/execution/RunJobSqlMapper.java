package com.stonewu.agenteam.mapper.execution;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.execution.entity.RunJobQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/**
 * query_value2 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface RunJobSqlMapper extends MPJBaseMapper<BackgroundJobRow> {
    default int enqueueBackgroundJob(String id, String enterprise, String user, String dedupeKey, String run,
                                     Timestamp now) {
        var databaseRow = new BackgroundJobRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setOwnerUserId(user);
        databaseRow.setKind("run");
        databaseRow.setDedupeKey(dedupeKey);
        databaseRow.setPayloadJson(JsonNodeFactory.instance.objectNode().put("runId", run).toString());
        databaseRow.setAvailableAt((now == null ? null : now.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    List<String> claimBackgroundJob(@Param("now") Timestamp now);

    default int assignLease(String owner, Timestamp until, Timestamp now, String id) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .set(BackgroundJobRow::getStatus, "leased").set(BackgroundJobRow::getLeaseOwner, owner)
            .setIncrBy(BackgroundJobRow::getLeaseVersion, 1).set(BackgroundJobRow::getLeaseUntil, until)
            .set(BackgroundJobRow::getHeartbeatAt, now).setIncrBy(BackgroundJobRow::getAttemptCount, 1)
            .set(BackgroundJobRow::getUpdatedAt, now));
    }

    List<RunJobQueryRow> claimBackgroundJob3(@Param("id") String id);

    default List<String> validBackgroundJob(String id, String enterpriseId, String userId, String owner, long version,
                                            Timestamp now, boolean lock) {
        if (lock) {
            return validBackgroundJobLocked(id, enterpriseId, userId, owner, version, now, lock);
        }
        var criteria = new LambdaQueryWrapper<BackgroundJobRow>().select(BackgroundJobRow::getId)
            .eq(BackgroundJobRow::getId, id).eq(BackgroundJobRow::getEnterpriseId, enterpriseId)
            .eq(BackgroundJobRow::getOwnerUserId, userId).eq(BackgroundJobRow::getKind, "run")
            .eq(BackgroundJobRow::getStatus, "leased").eq(BackgroundJobRow::getLeaseOwner, owner)
            .eq(BackgroundJobRow::getLeaseVersion, version).gt(BackgroundJobRow::getLeaseUntil, now);
        return selectList(criteria).stream().map(storedRow -> storedRow.getId()).toList();
    }

    List<String> validBackgroundJobLocked(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
                                          @Param("userId") String userId, @Param("owner") String owner,
                                          @Param("version") long version, @Param("now") Timestamp now,
                                          @Param("lock") boolean lock);

    default int renewBackgroundJob(Timestamp now, Timestamp until, String id, String owner, long version) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getStatus, "leased").eq(BackgroundJobRow::getLeaseOwner, owner)
            .eq(BackgroundJobRow::getLeaseVersion, version).gt(BackgroundJobRow::getLeaseUntil, now)
            .set(BackgroundJobRow::getHeartbeatAt, now).set(BackgroundJobRow::getLeaseUntil, until)
            .set(BackgroundJobRow::getUpdatedAt, now));
    }

    default int finishBackgroundJob(String status, String code, String error, Timestamp now, String enterprise,
                                    String dedupeKey) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getDedupeKey, dedupeKey).eq(BackgroundJobRow::getKind, "run")
            .in(BackgroundJobRow::getStatus, Arrays.asList("queued", "leased")).set(BackgroundJobRow::getStatus, status)
            .set(BackgroundJobRow::getErrorCode, code).set(BackgroundJobRow::getErrorSummary, error)
            .set(BackgroundJobRow::getLeaseUntil, null).set(BackgroundJobRow::getUpdatedAt, now));
    }

    List<RunJobQueryRow> expiredBackgroundJob(@Param("now") Timestamp now, @Param("limit") int limit);

    List<String> expiredAndLockedBackgroundJob(@Param("id") String id, @Param("owner") String owner,
                                               @Param("version") long version, @Param("now") Timestamp now);

    default int requeueBackgroundJob(Timestamp now, String id, long version) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getStatus, "leased").eq(BackgroundJobRow::getLeaseVersion, version)
            .set(BackgroundJobRow::getStatus, "queued").set(BackgroundJobRow::getLeaseOwner, null)
            .set(BackgroundJobRow::getLeaseUntil, null).set(BackgroundJobRow::getAvailableAt, now)
            .set(BackgroundJobRow::getUpdatedAt, now));
    }

    default int parkBackgroundJob(Timestamp now, String id, long version) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getId, id)
            .eq(BackgroundJobRow::getLeaseVersion, version).eq(BackgroundJobRow::getStatus, "leased")
            .set(BackgroundJobRow::getStatus, "completed").set(BackgroundJobRow::getLeaseOwner, null)
            .set(BackgroundJobRow::getLeaseUntil, null).set(BackgroundJobRow::getUpdatedAt, now));
    }

    default int resumeBackgroundJob(Timestamp now, String enterprise, String dedupeKey) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getDedupeKey, dedupeKey)
            .eq(BackgroundJobRow::getStatus, "completed").set(BackgroundJobRow::getStatus, "queued")
            .set(BackgroundJobRow::getAvailableAt, now).set(BackgroundJobRow::getLeaseOwner, null)
            .set(BackgroundJobRow::getLeaseUntil, null).set(BackgroundJobRow::getUpdatedAt, now));
    }

    default int retryBackgroundJob(Timestamp available, Timestamp now, String enterprise, String dedupeKey) {
        return update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterprise)
            .eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getDedupeKey, dedupeKey)
            .eq(BackgroundJobRow::getStatus, "leased").set(BackgroundJobRow::getStatus, "queued")
            .set(BackgroundJobRow::getAvailableAt, available).set(BackgroundJobRow::getLeaseOwner, null)
            .set(BackgroundJobRow::getLeaseUntil, null).set(BackgroundJobRow::getErrorCode, null)
            .set(BackgroundJobRow::getErrorSummary, null).set(BackgroundJobRow::getUpdatedAt, now));
    }

    List<RunJobQueryRow> lastEventBatchBackgroundJob(@Param("id") String id);

    int savedEventBatchBackgroundJob(@Param("id") String id, @Param("hash") String hash, @Param("id2") String id2,
                                     @Param("version") long version);

    List<RunJobQueryRow> queuedBeforeBackgroundJob(@Param("boundary") Timestamp boundary, @Param("limit") int limit);

    default int removeBackgroundJob(String enterpriseId, String dedupeKey) {
        return delete(new LambdaQueryWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, enterpriseId)
            .eq(BackgroundJobRow::getKind, "run").eq(BackgroundJobRow::getDedupeKey, dedupeKey));
    }
}

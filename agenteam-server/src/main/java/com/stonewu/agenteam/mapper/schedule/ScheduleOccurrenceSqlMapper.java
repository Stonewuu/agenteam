package com.stonewu.agenteam.mapper.schedule;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleOccurrenceQueryRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/**
 * ScheduleOccurrenceMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ScheduleOccurrenceSqlMapper extends MPJBaseMapper<ScheduledOccurrenceRow> {
    ScheduledOccurrenceRow lock(@Param("enterprise") String enterprise, @Param("schedule") String schedule, @Param("id") String id);

    List<ScheduleOccurrenceQueryRow> listOccurrences(@Param("enterprise") String enterprise,
                                                     @Param("schedule") String schedule,
                                                     @Param("cursor") PagePosition cursor, @Param("limit") int limit);

    List<ScheduleOccurrenceQueryRow> findRunAttempt(@Param("enterprise") String enterprise,
                                                    @Param("schedule") String schedule, @Param("key") String key);

    List<ScheduleOccurrenceQueryRow> forRunRunAttempt(@Param("enterprise") String enterprise, @Param("run") String run);

    List<ScheduleOccurrenceQueryRow> activeRunAttempt(@Param("enterprise") String enterprise,
                                                      @Param("schedule") String schedule, @Param("id") String id);

    default int createScheduledOccurrence(String id, String enterprise, String schedule, String kind, String key,
                                          Timestamp scheduledFor, String runId, String conversationId, String status,
                                          String reason, Timestamp finishedAt, Timestamp now) {
        var databaseRow = new ScheduledOccurrenceRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setScheduleId(schedule);
        databaseRow.setTriggerKind(kind);
        databaseRow.setOccurrenceKey(key);
        databaseRow.setScheduledFor((scheduledFor == null ? null : scheduledFor.toInstant()));
        databaseRow.setRunId(runId);
        databaseRow.setConversationId(conversationId);
        databaseRow.setStatus(status);
        databaseRow.setReasonCode(reason);
        databaseRow.setFinishedAt((finishedAt == null ? null : finishedAt.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int stateScheduledOccurrence(String status, String errorCode, Timestamp startedAt, Timestamp time2,
                                         Timestamp now, String enterpriseId, String id) {
        return update(
            new LambdaUpdateWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getEnterpriseId, enterpriseId)
                .eq(ScheduledOccurrenceRow::getRunId, id)
                .in(ScheduledOccurrenceRow::getStatus, Arrays.asList("queued", "running", "waiting_approval"))
                .set(ScheduledOccurrenceRow::getStatus, status).set(ScheduledOccurrenceRow::getReasonCode, errorCode)
                .set(ScheduledOccurrenceRow::getStartedAt, startedAt).set(ScheduledOccurrenceRow::getFinishedAt, time2)
                .set(ScheduledOccurrenceRow::getUpdatedAt, now));
    }

    List<ScheduleOccurrenceQueryRow> latestRunAttempt(@Param("enterprise") String enterprise,
                                                      @Param("schedule") String schedule);

    List<ScheduleOccurrenceQueryRow> summaries(@Param("enterprise") String enterprise, @Param("schedules") List<String> schedules);
}

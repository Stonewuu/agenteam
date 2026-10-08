package com.stonewu.agenteam.mapper.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.stonewu.agenteam.mapper.query.LikePattern;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalQueryRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduleDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleQueryRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * ScheduleMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ScheduleSqlMapper extends MPJBaseMapper<ScheduledTaskRow> {
    default List<ScheduleQueryRow> listSchedules(String enterprise, String user, String name, PagePosition cursor,
                                                 int limit) {
        var query = scheduleQuery(enterprise, user).isNull(ScheduledTaskRow::getDeletedAt)
            .like(ScheduledTaskRow::getName, LikePattern.escapeWildcards(name));
        if (cursor != null) {
            query.and(part -> part.lt(ScheduledTaskRow::getUpdatedAt, cursor.time())
                .or(equal -> equal.eq(ScheduledTaskRow::getUpdatedAt, cursor.time())
                    .lt(ScheduledTaskRow::getId, cursor.id())));
        }
        query.orderByDesc(ScheduledTaskRow::getUpdatedAt, ScheduledTaskRow::getId);
        return selectJoinPage(new Page<ScheduleQueryRow>(1, limit, false), ScheduleQueryRow.class, query).getRecords();
    }

    default int createSchedule(String id, String enterprise, String user, ScheduleDefinition value, String frequency,
                               String weekdays, String timezone, Instant now) {
        var databaseRow = new ScheduledTaskRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setOwnerUserId(user);
        databaseRow.setActionType(value.actionType());
        databaseRow.setActionSchemaVersion(value.actionSchemaVersion());
        databaseRow.setActionConfigJson(value.actionConfigJson());
        databaseRow.setHireId(value.hireId());
        databaseRow.setAgentVersionId(value.agentVersionId());
        databaseRow.setName(value.name());
        databaseRow.setInputText(value.inputText());
        databaseRow.setFrequency(frequency);
        databaseRow.setLocalDate(value.rule().date());
        databaseRow.setLocalTime(Objects.toString(value.rule().time(), null));
        databaseRow.setWeekdaysJson(weekdays);
        databaseRow.setMonthDay(value.rule().monthDay());
        databaseRow.setTimezone(timezone);
        databaseRow.setEnabled((value.enabled() ? 1 : 0));
        databaseRow.setMaxRetries(value.maxRetries());
        databaseRow.setNextRunAt(value.nextRunAt());
        databaseRow.setLastCheckedAt(now);
        databaseRow.setCreatedAt(now);
        databaseRow.setUpdatedAt(now);
        return insert(databaseRow);
    }

    default int updateSchedule(ScheduleRecord before, ScheduleDefinition value, String frequency, String weekdays,
                               String timezone, Instant now) {
        return update(
            new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, before.enterpriseId())
                .eq(ScheduledTaskRow::getOwnerUserId, before.userId()).eq(ScheduledTaskRow::getId, before.id())
                .eq(ScheduledTaskRow::getRevision, before.revision()).isNull(ScheduledTaskRow::getDeletedAt)
                .set(ScheduledTaskRow::getActionType, value.actionType())
                .set(ScheduledTaskRow::getActionSchemaVersion, value.actionSchemaVersion())
                .set(ScheduledTaskRow::getActionConfigJson, value.actionConfigJson())
                .set(ScheduledTaskRow::getHireId, value.hireId())
                .set(ScheduledTaskRow::getAgentVersionId, value.agentVersionId())
                .set(ScheduledTaskRow::getName, value.name()).set(ScheduledTaskRow::getInputText, value.inputText())
                .set(ScheduledTaskRow::getFrequency, frequency).set(ScheduledTaskRow::getLocalDate, value.rule().date())
                .set(ScheduledTaskRow::getLocalTime, value.rule().time())
                .set(ScheduledTaskRow::getWeekdaysJson, weekdays)
                .set(ScheduledTaskRow::getMonthDay, value.rule().monthDay())
                .set(ScheduledTaskRow::getTimezone, timezone).set(ScheduledTaskRow::getEnabled, value.enabled())
                .set(ScheduledTaskRow::getMaxRetries, value.maxRetries())
                .set(ScheduledTaskRow::getNextRunAt, value.nextRunAt()).set(ScheduledTaskRow::getLastCheckedAt, now)
                .set(ScheduledTaskRow::getUpdatedAt, now).set(ScheduledTaskRow::getPauseReason, null)
                .setIncrBy(ScheduledTaskRow::getRevision, 1));
    }

    default List<ScheduleQueryRow> findScheduledTask(String enterprise, String user, String id, boolean includeDeleted,
                                                     boolean lock) {
        if (lock) {
            return findScheduledTaskLocked(enterprise, user, id, includeDeleted);
        }
        var query = scheduleQuery(enterprise, user).eq(ScheduledTaskRow::getId, id);
        if (!includeDeleted) {
            query.isNull(ScheduledTaskRow::getDeletedAt);
        }
        return selectJoinList(ScheduleQueryRow.class, query);
    }

    List<ScheduleQueryRow> findScheduledTaskLocked(@Param("enterprise") String enterprise, @Param("user") String user,
                                                   @Param("id") String id,
                                                   @Param("includeDeleted") boolean includeDeleted);

    default List<Long> countEnabledScheduledTask(String enterprise, String user) {
        return List.of(selectCount(
            new LambdaQueryWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterprise)
                .eq(ScheduledTaskRow::getOwnerUserId, user).isNull(ScheduledTaskRow::getDeletedAt)
                .eq(ScheduledTaskRow::getEnabled, true)));
    }

    default int enabledScheduledTask(boolean enabled, Timestamp next, Timestamp now, String enterpriseId, String userId,
                                     String id, long revision) {
        return update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterpriseId)
            .eq(ScheduledTaskRow::getOwnerUserId, userId).eq(ScheduledTaskRow::getId, id)
            .eq(ScheduledTaskRow::getRevision, revision).isNull(ScheduledTaskRow::getDeletedAt)
            .set(ScheduledTaskRow::getEnabled, enabled).set(ScheduledTaskRow::getNextRunAt, next)
            .set(ScheduledTaskRow::getPauseReason, null).set(ScheduledTaskRow::getLastCheckedAt, now)
            .set(ScheduledTaskRow::getUpdatedAt, now).setIncrBy(ScheduledTaskRow::getRevision, 1));
    }

    default int upgradeScheduledTask(String version, Timestamp now, String enterpriseId, String userId, String id,
                                     long revision) {
        return update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterpriseId)
            .eq(ScheduledTaskRow::getOwnerUserId, userId).eq(ScheduledTaskRow::getId, id)
            .eq(ScheduledTaskRow::getRevision, revision).isNull(ScheduledTaskRow::getDeletedAt)
            .set(ScheduledTaskRow::getAgentVersionId, version).set(ScheduledTaskRow::getUpdatedAt, now)
            .setIncrBy(ScheduledTaskRow::getRevision, 1));
    }

    default int deleteScheduledTask(Timestamp now, String enterpriseId, String userId, String id, long revision) {
        return update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterpriseId)
            .eq(ScheduledTaskRow::getId, id)
            .eq(ScheduledTaskRow::getOwnerUserId, userId).eq(ScheduledTaskRow::getRevision, revision)
            .eq(ScheduledTaskRow::getEnabled, 0).isNull(ScheduledTaskRow::getActiveOccurrenceId)
            .isNull(ScheduledTaskRow::getDeletedAt).set(ScheduledTaskRow::getDeletedAt, now)
            .set(ScheduledTaskRow::getDeletedToken, id)
            .setIncrBy(ScheduledTaskRow::getRevision, 1).set(ScheduledTaskRow::getUpdatedAt, now));
    }

    default List<ScheduleQueryRow> candidatesScheduledTask(Timestamp now, Timestamp checkedBefore, int limit) {
        var criteria = new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getEnterpriseId,
                ScheduledTaskRow::getOwnerUserId, ScheduledTaskRow::getId).orderByAsc(ScheduledTaskRow::getLastCheckedAt)
            .orderByAsc(ScheduledTaskRow::getId).isNull(ScheduledTaskRow::getDeletedAt)
            .eq(ScheduledTaskRow::getEnabled, true).and(group -> group.le(ScheduledTaskRow::getNextRunAt, now)
                .or(other -> other.le(ScheduledTaskRow::getLastCheckedAt, checkedBefore)));
        long pageSize = limit;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<ScheduledTaskRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new ScheduleQueryRow();
                if (storedRow.getEnterpriseId() != null) {
                    mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
                }
                if (storedRow.getOwnerUserId() != null) {
                    mappedRow.setOwnerUserId(storedRow.getOwnerUserId());
                }
                if (storedRow.getId() != null) {
                    mappedRow.setId(storedRow.getId());
                }
                return mappedRow;
            }).toList();
    }

    default List<ScheduleQueryRow> enabledForEnterpriseScheduledTask(String enterprise) {
        var criteria = JoinWrappers.lambda(ScheduledTaskRow.class).selectAll(ScheduledTaskRow.class)
            .select(AgentHireRow::getAgentId).selectAs(ResourceVersionRow::getName, ScheduleQueryRow::getAgentName)
            .selectAs(ResourceVersionRow::getVersionNo, ScheduleQueryRow::getAgentVersionNo)
            .selectAs(ResourceVersionRow::getConfigJson, ScheduleQueryRow::getAgentConfigJson)
            .leftJoin(AgentHireRow.class, on -> on.eq(AgentHireRow::getEnterpriseId, ScheduledTaskRow::getEnterpriseId)
                .eq(AgentHireRow::getUserId, ScheduledTaskRow::getOwnerUserId)
                .eq(AgentHireRow::getId, ScheduledTaskRow::getHireId)).leftJoin(ResourceVersionRow.class,
                on -> on.eq(ResourceVersionRow::getEnterpriseId, ScheduledTaskRow::getEnterpriseId)
                    .eq(ResourceVersionRow::getId, ScheduledTaskRow::getAgentVersionId))
            .eq(ScheduledTaskRow::getEnterpriseId, enterprise).eq(ScheduledTaskRow::getEnabled, true)
            .isNull(ScheduledTaskRow::getDeletedAt).orderByAsc(ScheduledTaskRow::getId);
        return selectJoinList(ScheduleQueryRow.class, criteria);
    }


    default int checkedScheduledTask(Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterpriseId)
            .eq(ScheduledTaskRow::getId, id).set(ScheduledTaskRow::getLastCheckedAt, now));
    }

    default int advanceScheduledTask(boolean enabled, Timestamp next, String pauseReason, Timestamp now,
                                     String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterpriseId)
            .eq(ScheduledTaskRow::getId, id).isNull(ScheduledTaskRow::getDeletedAt)
            .set(ScheduledTaskRow::getEnabled, enabled).set(ScheduledTaskRow::getNextRunAt, next)
            .set(ScheduledTaskRow::getPauseReason, pauseReason).set(ScheduledTaskRow::getLastCheckedAt, now)
            .set(ScheduledTaskRow::getUpdatedAt, now).setIncrBy(ScheduledTaskRow::getRevision, 1));
    }

    default int activateScheduledTask(String occurrence, Timestamp now, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterpriseId)
            .eq(ScheduledTaskRow::getId, id).isNull(ScheduledTaskRow::getActiveOccurrenceId)
            .isNull(ScheduledTaskRow::getDeletedAt).set(ScheduledTaskRow::getActiveOccurrenceId, occurrence)
            .set(ScheduledTaskRow::getUpdatedAt, now).setIncrBy(ScheduledTaskRow::getRevision, 1));
    }

    default int releaseScheduledTask(Timestamp now, String enterprise, String schedule, String occurrence) {
        return update(new LambdaUpdateWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterprise)
            .eq(ScheduledTaskRow::getId, schedule).eq(ScheduledTaskRow::getActiveOccurrenceId, occurrence)
            .set(ScheduledTaskRow::getActiveOccurrenceId, null).set(ScheduledTaskRow::getUpdatedAt, now)
            .setIncrBy(ScheduledTaskRow::getRevision, 1));
    }

    default List<MemberRemovalQueryRow> schedulesScheduledTask(String enterprise, String user) {
        var criteria = new LambdaQueryWrapper<ScheduledTaskRow>().select(ScheduledTaskRow::getId,
                ScheduledTaskRow::getRevision).orderByAsc(ScheduledTaskRow::getId)
            .eq(ScheduledTaskRow::getEnterpriseId, enterprise).eq(ScheduledTaskRow::getOwnerUserId, user)
            .eq(ScheduledTaskRow::getEnabled, true).isNull(ScheduledTaskRow::getDeletedAt);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new MemberRemovalQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            return mappedRow;
        }).toList();
    }

    private static MPJLambdaWrapper<ScheduledTaskRow> scheduleQuery(String enterprise, String user) {
        return JoinWrappers.lambda(ScheduledTaskRow.class).selectAll(ScheduledTaskRow.class)
            .select(AgentHireRow::getAgentId).selectAs(ResourceVersionRow::getName, ScheduleQueryRow::getAgentName)
            .selectAs(ResourceVersionRow::getVersionNo, ScheduleQueryRow::getAgentVersionNo)
            .selectAs(ResourceVersionRow::getConfigJson, ScheduleQueryRow::getAgentConfigJson)
            .leftJoin(AgentHireRow.class, on -> on.eq(AgentHireRow::getEnterpriseId, ScheduledTaskRow::getEnterpriseId)
                .eq(AgentHireRow::getUserId, ScheduledTaskRow::getOwnerUserId)
                .eq(AgentHireRow::getId, ScheduledTaskRow::getHireId))
            .leftJoin(ResourceVersionRow.class,
                on -> on.eq(ResourceVersionRow::getEnterpriseId, ScheduledTaskRow::getEnterpriseId)
                    .eq(ResourceVersionRow::getId, ScheduledTaskRow::getAgentVersionId))
            .eq(ScheduledTaskRow::getEnterpriseId, enterprise).eq(ScheduledTaskRow::getOwnerUserId, user);
    }
}

package com.stonewu.agenteam.mapper.schedule;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleQueryRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule.Frequency;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 私有计划查询始终带企业和本人条件，修改在企业与计划锁内完成。
 */
@Repository
public class ScheduleMapper {
    private final ScheduleSqlMapper statements;
    private final ResourceJson json;

    public ScheduleMapper(ScheduleSqlMapper statements, ResourceJson json) {
        this.statements = statements;
        this.json = json;
    }

    public Optional<ScheduleRecord> find(String enterprise, String user, String id, boolean lock,
                                         boolean includeDeleted) {
        return statements.findScheduledTask(enterprise, user, id, includeDeleted, lock).stream().map(this::map)
            .findFirst();
    }

    public List<ScheduleRecord> list(String enterprise, String user, String query, PagePosition cursor, int limit) {
        return statements.listSchedules(enterprise, user, query, cursor, limit + 1).stream().map(this::map).toList();
    }

    public void create(String id, String enterprise, String user, ScheduleDefinition value, Instant now) {
        statements.createSchedule(id, enterprise, user, value, value.rule().frequency().name().toLowerCase(Locale.ROOT),
            encodeWeekdays(value), value.rule().timezone().getId(), now);
    }

    public long countEnabled(String enterprise, String user) {
        return DataAccessUtils.nullableSingleResult(statements.countEnabledScheduledTask(enterprise, user));
    }

    public void update(ScheduleRecord before, ScheduleDefinition value, Instant now) {
        int changed = statements.updateSchedule(before, value, value.rule().frequency().name().toLowerCase(Locale.ROOT),
            encodeWeekdays(value), value.rule().timezone().getId(), now);
        if (changed != 1) {
            throw new IllegalStateException("已锁定计划的修改版本发生变化，当前操作未提交");
        }
    }

    public void enabled(ScheduleRecord before, boolean enabled, Instant next, Instant now) {
        int changed = statements.enabledScheduledTask(enabled, timestamp(next), timestamp(now), before.enterpriseId(),
            before.userId(), before.id(), before.revision());
        if (changed != 1) {
            throw new IllegalStateException("已锁定计划的修改版本发生变化，当前操作未提交");
        }
    }

    public void upgrade(ScheduleRecord before, String version, Instant now) {
        int changed = statements.upgradeScheduledTask(version, timestamp(now), before.enterpriseId(), before.userId(),
            before.id(), before.revision());
        if (changed != 1) {
            throw new IllegalStateException("已锁定计划的修改版本发生变化，当前操作未提交");
        }
    }

    public void delete(ScheduleRecord before, Instant now) {
        int changed = statements.deleteScheduledTask(timestamp(now), before.enterpriseId(), before.userId(),
            before.id(), before.revision());
        if (changed != 1) {
            throw new IllegalStateException("计划仍在执行或已被修改，当前删除未提交");
        }
    }

    public record Candidate(String enterprise, String user, String id) {
    }

    public List<Candidate> candidates(Instant now, Instant checkedBefore, int limit) {
        return statements.candidatesScheduledTask(timestamp(now), timestamp(checkedBefore), limit).stream()
            .map(row -> new Candidate(row.getEnterpriseId(), row.getOwnerUserId(), row.getId())).toList();
    }

    public List<ScheduleRecord> enabledForEnterprise(String enterprise) {
        return statements.enabledForEnterpriseScheduledTask(enterprise).stream().map(this::map).toList();
    }

    public void checked(ScheduleRecord value, Instant now) {
        statements.checkedScheduledTask(timestamp(now), value.enterpriseId(), value.id());
    }

    public void advance(ScheduleRecord value, Instant next, String pauseReason, Instant now) {
        statements.advanceScheduledTask(next != null && pauseReason == null, timestamp(next), pauseReason,
            timestamp(now), value.enterpriseId(), value.id());
    }

    public void activate(ScheduleRecord value, String occurrence, Instant now) {
        int changed = statements.activateScheduledTask(occurrence, timestamp(now), value.enterpriseId(), value.id());
        if (changed != 1) {
            throw new IllegalStateException("计划已有未结束的发生记录，不能再次执行");
        }
    }

    public void release(String enterprise, String schedule, String occurrence, Instant now) {
        statements.releaseScheduledTask(timestamp(now), enterprise, schedule, occurrence);
    }

    private String encodeWeekdays(ScheduleDefinition value) {
        return json.write(json.tree(value.rule().weekdays().stream().map(DayOfWeek::getValue).sorted().toList()));
    }

    private ScheduleRecord map(ScheduleQueryRow rows) {
        var config = rows.getAgentConfigJson() == null ? json.tree(Map.of()) : json.read(rows.getAgentConfigJson());
        var days = EnumSet.noneOf(DayOfWeek.class);
        json.read(rows.getWeekdaysJson()).forEach(day -> days.add(DayOfWeek.of(day.asInt())));
        var rule = new ScheduleRule(Frequency.valueOf(rows.getFrequency().toUpperCase(Locale.ROOT)),
            rows.getLocalDate(),
            rows.getLocalTime(), days, rows.getMonthDay(), ZoneId.of(rows.getTimezone()));
        return new ScheduleRecord(rows.getId(), rows.getEnterpriseId(), rows.getOwnerUserId(), rows.getHireId(),
            rows.getAgentVersionId(),
            rows.getName(), rows.getInputText(), rule, rows.getEnabled(), rows.getMaxRetries(),
            instant(rows.getNextRunAt()),
            instant(rows.getLastCheckedAt()), rows.getPauseReason(), rows.getActiveOccurrenceId(), rows.getRevision(),
            instant(rows.getCreatedAt()),
            instant(rows.getUpdatedAt()), instant(rows.getDeletedAt()), rows.getAgentId(), rows.getAgentName(),
            rows.getAgentVersionNo(),
            config.path("icon").asText(null), config.path("color").asText(null), rows.getActionType(), rows.getActionSchemaVersion(), rows.getActionConfigJson());
    }
}

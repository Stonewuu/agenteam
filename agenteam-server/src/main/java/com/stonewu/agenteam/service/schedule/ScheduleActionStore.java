package com.stonewu.agenteam.service.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceSqlMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 固定本次参数并保存操作结果；调用方按企业、计划、发生记录的顺序持锁。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ScheduleActionStore {
    public static final Set<String> ACTIVE = Set.of("queued", "running", "waiting_approval");
    private final ScheduleOccurrenceSqlMapper occurrences;
    private final ScheduleMapper schedules;
    private final BackgroundJobMapper jobs;
    private final ScheduleActionRegistry actions;
    private final ResourceJson json;
    private final Clock clock;
    private final ScheduleSqlMapper planRows;

    public ScheduleActionStore(ScheduleOccurrenceSqlMapper occurrences, ScheduleMapper schedules, BackgroundJobMapper jobs,
                                ScheduleActionRegistry actions, ResourceJson json, Clock clock, ScheduleSqlMapper planRows) {
        this.occurrences = occurrences;
        this.schedules = schedules;
        this.jobs = jobs;
        this.actions = actions;
        this.json = json;
        this.clock = clock;
        this.planRows = planRows;
    }

    public String create(ScheduleRecord plan, String kind, String key, Instant time, String status, String reason) {
        var row = new ScheduledOccurrenceRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(plan.enterpriseId());
        row.setScheduleId(plan.id());
        row.setTriggerKind(kind);
        row.setOccurrenceKey(key);
        row.setScheduledFor(time);
        row.setActionType(plan.actionType());
        row.setActionSchemaVersion(plan.actionSchemaVersion());
        row.setScheduleRevision(plan.revision());
        row.setActionSnapshotJson(json.write(actions.require(plan.actionType(), plan.actionSchemaVersion()).snapshot(plan)));
        row.setSnapshotOrigin("captured");
        row.setActionResultJson("{}");
        row.setStatus(status);
        row.setReasonCode(reason);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        if (ACTIVE.contains(status)) {
            String job = UUID.randomUUID().toString();
            jobs.enqueue(job, plan.enterpriseId(), plan.userId(), "scheduled_action", "scheduled-action:" + row.getId(),
                json.write(json.tree(Map.of("scheduleId", plan.id(), "occurrenceId", row.getId()))), clock.instant());
            row.setActionJobId(job);
        } else {
            row.setFinishedAt(clock.instant());
        }
        if (occurrences.insert(row) != 1) {
            throw new IllegalStateException("本次定时操作没有保存成功");
        }
        if (ACTIVE.contains(status)) {
            schedules.activate(plan, row.getId(), clock.instant());
        }
        return row.getId();
    }

    public ScheduledOccurrenceRow lock(String enterprise, String schedule, String id) {
        return occurrences.lock(enterprise, schedule, id);
    }

    public ScheduledOccurrenceRow find(String enterprise, String id) {
        return occurrences.selectOne(new LambdaQueryWrapper<ScheduledOccurrenceRow>().eq(ScheduledOccurrenceRow::getEnterpriseId, enterprise)
            .eq(ScheduledOccurrenceRow::getId, id));
    }

    /** 仅供已持企业锁的后台工作定位所属计划，用户接口仍先检查本人归属。 */
    public ScheduleRecord lockPlan(String enterprise, String id) {
        var plan = planRows.selectOne(new LambdaQueryWrapper<ScheduledTaskRow>().eq(ScheduledTaskRow::getEnterpriseId, enterprise)
            .eq(ScheduledTaskRow::getId, id));
        return plan == null ? null : schedules.find(enterprise, plan.getOwnerUserId(), id, true, true).orElse(null);
    }

    public boolean prepared(ScheduledOccurrenceRow row) {
        return json.read(row.getActionResultJson()).path("prepared").asBoolean(false);
    }

    public void apply(ScheduledOccurrenceRow row, ScheduleActionSubmission outcome) {
        row.setStatus(outcome.status());
        row.setReasonCode(outcome.reasonCode());
        row.setErrorSummary(outcome.errorSummary());
        row.setActionResultJson(json.write(outcome.result()));
        if (row.getStartedAt() == null) {
            row.setStartedAt(clock.instant());
        }
        if (outcome.run() != null) {
            row.setRunId(outcome.run().runId());
            row.setConversationId(outcome.run().conversationId());
        }
        save(row);
        if (!ACTIVE.contains(row.getStatus())) {
            schedules.release(row.getEnterpriseId(), row.getScheduleId(), row.getId(), clock.instant());
        }
    }

    public void save(ScheduledOccurrenceRow row) {
        row.setFinishedAt(ACTIVE.contains(row.getStatus()) ? null : clock.instant());
        row.setUpdatedAt(clock.instant());
        int changed = occurrences.update(new LambdaUpdateWrapper<ScheduledOccurrenceRow>()
            .eq(ScheduledOccurrenceRow::getEnterpriseId, row.getEnterpriseId()).eq(ScheduledOccurrenceRow::getScheduleId, row.getScheduleId())
            .eq(ScheduledOccurrenceRow::getId, row.getId()).set(ScheduledOccurrenceRow::getStatus, row.getStatus())
            .set(ScheduledOccurrenceRow::getReasonCode, row.getReasonCode()).set(ScheduledOccurrenceRow::getErrorSummary, row.getErrorSummary())
            .set(ScheduledOccurrenceRow::getActionResultJson, row.getActionResultJson()).set(ScheduledOccurrenceRow::getRunId, row.getRunId())
            .set(ScheduledOccurrenceRow::getConversationId, row.getConversationId()).set(ScheduledOccurrenceRow::getStartedAt, row.getStartedAt())
            .set(ScheduledOccurrenceRow::getFinishedAt, row.getFinishedAt()).set(ScheduledOccurrenceRow::getUpdatedAt, row.getUpdatedAt()));
        if (changed != 1) {
            throw new IllegalStateException("已锁定的定时操作没有保存成功");
        }
    }

    /** 业务数据写完才锁工作记录，旧租约的全部业务写入都会随异常回滚。 */
    public void finish(JobLease lease, String status, String code, String summary, Instant availableAt) {
        if (!jobs.lockOwned(lease, clock.instant()) || !jobs.finish(lease, status, code, summary, availableAt, clock.instant())) {
            throw new LeaseLostException();
        }
    }

    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public List<ScheduledOccurrenceRow> candidates() {
        return occurrences.selectPage(new Page<ScheduledOccurrenceRow>(1, 100, false), new LambdaQueryWrapper<ScheduledOccurrenceRow>()
            .in(ScheduledOccurrenceRow::getStatus, ACTIVE).eq(ScheduledOccurrenceRow::getSnapshotOrigin, "captured")
            .isNull(ScheduledOccurrenceRow::getRunId).isNotNull(ScheduledOccurrenceRow::getActionJobId)
            .orderByAsc(ScheduledOccurrenceRow::getUpdatedAt, ScheduledOccurrenceRow::getId)).getRecords();
    }

    public static class LeaseLostException extends RuntimeException {
        public LeaseLostException() {
            super("定时操作的工作租约已变化，本次提交已回滚");
        }
    }
}

package com.stonewu.agenteam.service.schedule;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.background.BackgroundJobSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.BackgroundJobRow;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/** 只停止尚未开始的工作，已送达内容和正在请求的平台结果仍如实保留。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ScheduleOccurrenceCancellation {
    private final SchedulePolicy policy;
    private final ScheduleActionStore store;
    private final ScheduleActionRegistry actions;
    private final BackgroundJobSqlMapper jobs;
    private final ScheduleOccurrenceMapper occurrences;
    private final ScheduleViewMapper views;
    private final ResourceJson json;
    private final AuditEventService audit;
    private final Clock clock;

    public ScheduleOccurrenceCancellation(SchedulePolicy policy, ScheduleActionStore store, ScheduleActionRegistry actions,
                                            BackgroundJobSqlMapper jobs, ScheduleOccurrenceMapper occurrences, ScheduleViewMapper views,
                                            ResourceJson json, AuditEventService audit, Clock clock) {
        this.policy = policy;
        this.store = store;
        this.actions = actions;
        this.jobs = jobs;
        this.occurrences = occurrences;
        this.views = views;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    public ScheduleOccurrenceView cancel(AuthContext actor, String schedule, String id) {
        return cancel(actor, schedule, id, false);
    }

    public ScheduleOccurrenceView cancel(AuthContext actor, String schedule, String id, boolean userActivity) {
        policy.manage(actor);
        policy.require(actor, schedule, true, false);
        var row = store.lock(actor.enterpriseId(), schedule, id);
        if (row == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (ScheduleActionStore.ACTIVE.contains(row.getStatus())) {
            var result = (ObjectNode) json.read(row.getActionResultJson()).deepCopy();
            result.put("cancelRequested", true);
            if (!store.prepared(row) && row.getRunId() == null) {
                store.apply(row, new ScheduleActionSubmission("cancelled", "SCHEDULE_CANCELLED", null, null, result));
                int stopped = jobs.update(new LambdaUpdateWrapper<BackgroundJobRow>().eq(BackgroundJobRow::getEnterpriseId, actor.enterpriseId())
                    .eq(BackgroundJobRow::getId, row.getActionJobId()).eq(BackgroundJobRow::getKind, "scheduled_action")
                    .in(BackgroundJobRow::getStatus, "queued", "leased").set(BackgroundJobRow::getStatus, "cancelled")
                    .set(BackgroundJobRow::getLeaseOwner, null).set(BackgroundJobRow::getLeaseUntil, null).set(BackgroundJobRow::getHeartbeatAt, null)
                    .set(BackgroundJobRow::getUpdatedAt, clock.instant()));
                if (stopped != 1) {
                    throw new IllegalStateException("定时操作准备工作状态已变化，停止操作未提交");
                }
            } else {
                row.setActionResultJson(json.write(result));
                store.save(row);
                var handler = actions.require(row.getActionType(), row.getActionSchemaVersion());
                handler.cancel(actor, row);
                var refreshed = store.lock(actor.enterpriseId(), schedule, id);
                handler.reconcile(refreshed).ifPresent(outcome -> store.apply(refreshed, outcome));
            }
            audit.record(actor.enterpriseId(), actor.user(), "schedule.cancel", "schedule", schedule, "停止本次定时操作",
                Map.of("occurrenceId", id, "userActivity", userActivity));
        }
        return views.view(occurrences.active(actor.enterpriseId(), schedule, id).orElseThrow());
    }
}

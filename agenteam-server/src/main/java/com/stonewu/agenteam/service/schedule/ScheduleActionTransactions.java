package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionSubmission;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/** 一次提交只处理数据库；异常必须离开事务，避免提交部分接收人的通知后才记录整体失败。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ScheduleActionTransactions {
    private final BackgroundJobMapper jobs;
    private final EnterpriseMapper enterprises;
    private final ScheduleMapper schedules;
    private final ScheduleActionStore store;
    private final ScheduleActionRegistry actions;
    private final ScheduleAccessService access;
    private final ScheduleRunStateService states;
    private final ResourceJson json;
    private final Clock clock;

    public ScheduleActionTransactions(BackgroundJobMapper jobs, EnterpriseMapper enterprises, ScheduleMapper schedules,
                                       ScheduleActionStore store, ScheduleActionRegistry actions, ScheduleAccessService access,
                                       ScheduleRunStateService states, ResourceJson json, Clock clock) {
        this.jobs = jobs;
        this.enterprises = enterprises;
        this.schedules = schedules;
        this.store = store;
        this.actions = actions;
        this.access = access;
        this.states = states;
        this.json = json;
        this.clock = clock;
    }

    public Optional<JobLease> claim(String worker) {
        return jobs.claim("scheduled_action", worker, clock.instant());
    }

    public boolean renew(JobLease lease) {
        return jobs.renew(lease, clock.instant());
    }

    public void submit(JobLease lease) {
        var locked = lock(lease);
        if (locked == null || !ScheduleActionStore.ACTIVE.contains(locked.occurrence().getStatus()) || store.prepared(locked.occurrence())) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        if (lease.exhausted()) {
            failed(locked, "SCHEDULE_PREPARATION_FAILED", "本次操作多次准备失败，请检查后重新执行。", "failed");
            store.finish(lease, "failed", "SCHEDULE_PREPARATION_FAILED", "本次操作多次准备失败。", clock.instant());
            return;
        }
        var actor = access.requireOwner(locked.plan());
        var row = locked.occurrence();
        var handler = actions.require(row.getActionType(), row.getActionSchemaVersion());
        store.apply(row, handler.submit(actor, row));
        store.finish(lease, "completed", null, null, clock.instant());
    }

    /** 提交事务已经整体回滚后，单独保存权限拒绝或可重试的系统失败。 */
    public void failure(JobLease lease, RuntimeException failure) {
        var locked = lock(lease);
        if (locked == null || !ScheduleActionStore.ACTIVE.contains(locked.occurrence().getStatus()) || store.prepared(locked.occurrence())) {
            store.finish(lease, "completed", null, null, clock.instant());
            return;
        }
        String code = "SCHEDULE_PREPARATION_FAILED";
        String summary = "本次操作暂时无法准备，稍后会继续尝试。";
        boolean terminal = lease.exhausted() || lease.attemptCount() >= lease.maxAttempts();
        String status = "failed";
        if (failure instanceof ResponseStatusException rejected) {
            code = rejected instanceof ApiException api && "SCHEDULE_OWNER_UNAVAILABLE".equals(api.code()) ? api.code()
                : actions.require(locked.occurrence().getActionType(), locked.occurrence().getActionSchemaVersion()).denialReason(rejected);
            summary = "执行所需的账号、权限或资源已不可用，本次操作未提交。";
            status = "blocked";
            terminal = true;
            var checked = access.check(locked.plan());
            if (locked.plan().enabled() && checked.reason() != null) {
                states.pause(locked.plan(), checked.reason());
            }
        }
        if (terminal) {
            failed(locked, code, summary, status);
        }
        store.finish(lease, terminal ? "failed" : "queued", code, summary, clock.instant().plusSeconds(30));
    }

    private void failed(Locked locked, String code, String summary, String status) {
        store.apply(locked.occurrence(), new ScheduleActionSubmission(status, code, summary, null,
            json.tree(Map.of("prepared", false))));
    }

    private Locked lock(JobLease lease) {
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow(() -> new IllegalStateException("定时操作所属企业记录不存在"));
        var payload = json.read(lease.payloadJson());
        var plan = schedules.find(lease.enterpriseId(), lease.ownerUserId(), payload.path("scheduleId").asText(), true, true).orElse(null);
        if (plan == null) {
            return null;
        }
        var occurrence = store.lock(lease.enterpriseId(), plan.id(), payload.path("occurrenceId").asText());
        if (occurrence == null || !lease.id().equals(occurrence.getActionJobId())) {
            return null;
        }
        return new Locked(plan, occurrence);
    }

    private record Locked(ScheduleRecord plan, ScheduledOccurrenceRow occurrence) {
    }
}

package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.response.ScheduleOccurrenceView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 先锁企业再锁计划，固定操作快照、持久工作和下次时间一同提交。
 */
@Service
public class ScheduleTriggerService {
    private final EnterpriseMapper enterprises;
    private final ScheduleMapper schedules;
    private final ScheduleOccurrenceMapper occurrences;
    private final ScheduleViewMapper views;
    private final SchedulePolicy policy;
    private final ScheduleAccessService access;
    private final ScheduleDuePlanner planner;
    private final ScheduleRunStateService states;
    private final ScheduleActionStore actionStore;
    private final Clock clock;
    private final AuditEventService audit;

    public ScheduleTriggerService(EnterpriseMapper enterprises, ScheduleMapper schedules,
                                  ScheduleOccurrenceMapper occurrences, ScheduleViewMapper views,
                                  SchedulePolicy policy, ScheduleAccessService access, ScheduleDuePlanner planner,
                                  ScheduleRunStateService states,
                                  ScheduleActionStore actionStore, Clock clock,
                                  AuditEventService audit) {
        this.enterprises = enterprises;
        this.schedules = schedules;
        this.occurrences = occurrences;
        this.views = views;
        this.policy = policy;
        this.access = access;
        this.planner = planner;
        this.states = states;
        this.actionStore = actionStore;
        this.clock = clock;
        this.audit = audit;
    }

    public List<ScheduleMapper.Candidate> candidates() {
        return schedules.candidates(clock.instant(), clock.instant().minus(Duration.ofDays(1)), 100);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void process(ScheduleMapper.Candidate candidate) {
        if (enterprises.lockEnterprise(candidate.enterprise()).isEmpty()) {
            return;
        }
        var plan = schedules.find(candidate.enterprise(), candidate.user(), candidate.id(), true, false).orElse(null);
        if (plan == null || !plan.enabled()) {
            return;
        }
        var now = clock.instant();
        if (plan.nextRunAt().isAfter(now) && plan.lastCheckedAt().isAfter(now.minus(Duration.ofDays(1)))) {
            return;
        }
        var due = planner.calculate(plan.rule(), plan.nextRunAt(), plan.lastCheckedAt(), now);
        if (due.pauseReason() != null) {
            states.pause(plan, due.pauseReason());
            return;
        }
        var checked = access.check(plan);
        if (checked.reason() != null) {
            states.pause(plan, checked.reason());
            return;
        }
        for (var missed : due.missed()) {
            record(plan, "scheduled", missed.toString(), missed, "missed", "SCHEDULE_MISSED");
        }
        if (due.executeAt() != null) {
            trigger(plan, "scheduled", due.executeAt().toString(), due.executeAt());
        }
        if (due.missed().isEmpty() && due.executeAt() == null && !due.disable()) {
            schedules.checked(plan, now);
        } else {
            schedules.advance(plan, due.nextRunAt(), null, now);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduleOccurrenceView manual(AuthContext actor, String id, String requestKey) {
        return manual(actor, id, requestKey, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduleOccurrenceView manual(AuthContext actor, String id, String requestKey, boolean userActivity) {
        policy.manage(actor);
        var plan = policy.require(actor, id, true, false);
        String key = "manual:" + requestKey;
        var existing = occurrences.find(actor.enterpriseId(), id, key);
        if (existing.isPresent()) {
            return views.view(existing.get());
        }
        if (plan.activeOccurrenceId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_BUSY", "此计划还有未结束的任务，请等待完成或先停止。");
        }
        var checked = access.check(plan);
        if (checked.reason() != null) {
            states.pause(plan, checked.reason());
            record(plan, "manual", key, clock.instant(), "blocked", checked.reason());
        } else {
            trigger(plan, "manual", key, clock.instant());
        }
        var result = views.view(occurrences.find(actor.enterpriseId(), id, key).orElseThrow());
        if (userActivity && ScheduleActionStore.ACTIVE.contains(result.status())) {
            audit.record(actor.enterpriseId(), actor.user(), "schedule.run", "schedule", id, "手动执行定时任务",
                Map.of("userActivity", true, "occurrenceId", result.id()));
        }
        return result;
    }

    private void trigger(ScheduleRecord plan, String kind, String key,
                         Instant time) {
        if (occurrences.find(plan.enterpriseId(), plan.id(), key).isPresent()) {
            return;
        }
        if (plan.activeOccurrenceId() != null) {
            record(plan, kind, key, time, "skipped", "SCHEDULE_PREVIOUS_ACTIVE");
            return;
        }
        actionStore.create(plan, kind, key, time, "queued", null);
    }

    private void record(ScheduleRecord plan, String kind, String key, Instant time, String status, String reason) {
        if (occurrences.find(plan.enterpriseId(), plan.id(), key).isEmpty()) {
            actionStore.create(plan, kind, key, time, status, reason);
        }
    }
}

package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleRuleMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.schedule.entity.ScheduleDefinition;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRule;
import com.stonewu.agenteam.model.schedule.entity.ScheduleActionDefinition;
import com.stonewu.agenteam.model.schedule.request.ScheduleWriteRequest;
import com.stonewu.agenteam.model.schedule.response.ScheduleView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 保存未来计划时固定员工版本；启停与删除不改写已发生的执行。
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ScheduleManagementService {
    private final SchedulePolicy policy;
    private final ScheduleMapper schedules;
    private final ScheduleRuleMapper rules;
    private final ScheduleTimeCalculator times;
    private final ScheduleQueryService queries;
    private final AuditEventService audit;
    private final Clock clock;
    private final ScheduleActionRegistry actions;
    private final ScheduleNotificationTargetService targets;

    public ScheduleManagementService(SchedulePolicy policy, ScheduleMapper schedules, ScheduleRuleMapper rules,
                                     ScheduleTimeCalculator times,
                                     ScheduleQueryService queries, AuditEventService audit, Clock clock,
                                     ScheduleActionRegistry actions, ScheduleNotificationTargetService targets) {
        this.policy = policy;
        this.schedules = schedules;
        this.rules = rules;
        this.times = times;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
        this.actions = actions;
        this.targets = targets;
    }

    public ScheduleView create(AuthContext actor, ScheduleWriteRequest input) {
        return create(actor, input, false);
    }

    /**
     * 用户接口显式标记人工操作；员工工具沿用不带标记的入口。
     */
    public ScheduleView create(AuthContext actor, ScheduleWriteRequest input, boolean userActivity) {
        policy.manage(actor);
        var definition = prepare(actor, input, null);
        String id = UUID.randomUUID().toString();
        schedules.create(id, actor.enterpriseId(), actor.userId(), definition.schedule(), clock.instant());
        targets.replace(actor.enterpriseId(), id, definition.action().recipients());
        audit.record(actor.enterpriseId(), actor.user(), "schedule.create", "schedule", id, "创建本人定时任务",
            Map.of("enabled", input.enabled(), "userActivity", userActivity));
        return queries.view(policy.require(actor, id, false, false));
    }

    public ScheduleView update(AuthContext actor, String id, ScheduleWriteRequest input, long revision) {
        return update(actor, id, input, revision, false);
    }

    public ScheduleView update(AuthContext actor, String id, ScheduleWriteRequest input, long revision,
                               boolean userActivity) {
        policy.manage(actor);
        var before = policy.require(actor, id, true, false);
        policy.revision(before, revision);
        var definition = prepare(actor, input, before);
        schedules.update(before, definition.schedule(), clock.instant());
        targets.replace(actor.enterpriseId(), id, definition.action().recipients());
        audit.record(actor.enterpriseId(), actor.user(), "schedule.update", "schedule", id,
            "修改本人计划的未来执行设置",
            Map.of("enabled", input.enabled(), "maxRetries", input.maxRetries(), "userActivity", userActivity));
        return queries.view(policy.require(actor, id, false, false));
    }

    public ScheduleView enabled(AuthContext actor, String id, boolean enabled, long revision) {
        return enabled(actor, id, enabled, revision, false);
    }

    public ScheduleView enabled(AuthContext actor, String id, boolean enabled, long revision, boolean userActivity) {
        policy.manage(actor);
        var before = policy.require(actor, id, true, false);
        policy.revision(before, revision);
        if (before.enabled() == enabled) {
            return queries.view(before);
        }
        if (enabled) {
            actions.require(before.actionType(), before.actionSchemaVersion()).validateCurrent(actor, before);
        }
        schedules.enabled(before, enabled, enabled ? next(before.rule()) : null, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "schedule.enabled", "schedule", id,
            enabled ? "启用本人定时任务" : "暂停本人计划的未来触发",
            Map.of("enabled", enabled, "userActivity", userActivity));
        return queries.view(policy.require(actor, id, false, false));
    }

    public ScheduleView upgrade(AuthContext actor, String id, long revision) {
        return upgrade(actor, id, revision, false);
    }

    public ScheduleView upgrade(AuthContext actor, String id, long revision, boolean userActivity) {
        policy.manage(actor);
        var before = policy.require(actor, id, true, false);
        policy.revision(before, revision);
        if (!actions.require(before.actionType(), before.actionSchemaVersion()).usesAgent()) {
            throw ApiException.invalidField("action.type", "此操作不使用智能体版本，无需升级员工版本。");
        }
        var selected = policy.select(actor, before.hireId(), null);
        if (selected.versionId().equals(before.agentVersionId())) {
            return queries.view(before);
        }
        schedules.upgrade(before, selected.versionId(), clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "schedule.upgrade", "schedule", id,
            "更新本人计划未来使用的员工版本",
            Map.of("beforeVersionId", before.agentVersionId(), "afterVersionId", selected.versionId(), "userActivity",
                userActivity));
        return queries.view(policy.require(actor, id, false, false));
    }

    public void delete(AuthContext actor, String id, long revision) {
        delete(actor, id, revision, false);
    }

    public void delete(AuthContext actor, String id, long revision, boolean userActivity) {
        policy.manage(actor);
        var before = policy.require(actor, id, true, false);
        policy.revision(before, revision);
        if (before.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_ENABLED", "请先暂停计划，再执行删除。");
        }
        if (before.activeOccurrenceId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_BUSY", "此计划还有未结束的任务，请先停止或等待结束。");
        }
        schedules.delete(before, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "schedule.delete", "schedule", id, "删除本人已暂停的定时任务",
            Map.of("userActivity", userActivity));
    }

    private Prepared prepare(AuthContext actor, ScheduleWriteRequest input, ScheduleRecord previous) {
        String name = ResourceInput.text(input.name(), "name", 80, true);
        var rule = rules.from(input);
        var action = actions.prepare(actor, input, previous);
        return new Prepared(new ScheduleDefinition(action.hireId(), action.agentVersionId(), name, action.inputText(), rule, input.enabled(),
            action.maxRetries(), input.enabled() ? next(rule) : null, action.type(), action.schemaVersion(), action.configJson()), action);
    }

    private record Prepared(ScheduleDefinition schedule, ScheduleActionDefinition action) {
    }

    private Instant next(ScheduleRule rule) {
        var upcoming = times.next(rule, clock.instant(), 1);
        if (upcoming.isEmpty()) {
            throw ApiException.invalidField("localDate", "此计划已经没有未来执行时间，请修改日期后再启用。");
        }
        return upcoming.getFirst().instant();
    }
}

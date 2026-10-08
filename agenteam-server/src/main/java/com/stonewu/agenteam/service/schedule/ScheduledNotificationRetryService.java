package com.stonewu.agenteam.service.schedule;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.model.notification.entity.NotificationRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 人工重试在锁发送记录之前重新占用原计划，同一计划不能出现两个活动发生记录。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ScheduledNotificationRetryService {
    private final ScheduleActionStore store;
    private final ScheduleMapper schedules;
    private final ResourceJson json;
    private final Clock clock;

    public ScheduledNotificationRetryService(ScheduleActionStore store, ScheduleMapper schedules, ResourceJson json, Clock clock) {
        this.store = store;
        this.schedules = schedules;
        this.json = json;
        this.clock = clock;
    }

    public void reserve(NotificationRow notice) {
        if (notice.getSourceOccurrenceId() == null) {
            return;
        }
        var reference = store.find(notice.getEnterpriseId(), notice.getSourceOccurrenceId());
        var plan = reference == null ? null : store.lockPlan(notice.getEnterpriseId(), reference.getScheduleId());
        if (plan == null || plan.deletedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_RETRY_UNAVAILABLE", "原计划已删除，不能再次发送本次通知。");
        }
        var row = store.lock(notice.getEnterpriseId(), plan.id(), reference.getId());
        if (plan.activeOccurrenceId() != null && !plan.activeOccurrenceId().equals(row.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_BUSY", "此计划正在执行另一次任务，请等待完成后再试。");
        }
        var result = (ObjectNode) json.read(row.getActionResultJson()).deepCopy();
        result.put("cancelRequested", false);
        row.setActionResultJson(json.write(result));
        row.setStatus("running");
        row.setReasonCode(null);
        row.setErrorSummary(null);
        store.save(row);
        if (plan.activeOccurrenceId() == null) {
            schedules.activate(plan, row.getId(), clock.instant());
        }
    }
}

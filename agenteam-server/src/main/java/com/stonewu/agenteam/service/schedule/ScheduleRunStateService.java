package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.notification.NotificationDeliveryMapper.Notice;
import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleOccurrenceMapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleViewMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.schedule.entity.ScheduleRecord;
import com.stonewu.agenteam.service.notification.NotificationDeliveryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 执行和发生状态共同提交；发送提醒只写后台任务，不能提前释放未结束计划。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ScheduleRunStateService {
    private final ScheduleMapper schedules;
    private final ScheduleOccurrenceMapper occurrences;
    private final ScheduleViewMapper views;
    private final NotificationDeliveryService notifications;
    private final Clock clock;

    public ScheduleRunStateService(ScheduleMapper schedules, ScheduleOccurrenceMapper occurrences,
                                   ScheduleViewMapper views, NotificationDeliveryService notifications, Clock clock) {
        this.schedules = schedules;
        this.occurrences = occurrences;
        this.views = views;
        this.notifications = notifications;
        this.clock = clock;
    }

    public void changed(RunRecord run) {
        if (!run.mode().equals("scheduled") && !run.mode().equals("manual_schedule")) {
            return;
        }
        var occurrence = occurrences.forRun(run.enterpriseId(), run.id())
            .orElseThrow(() -> new IllegalStateException("计划执行缺少发生记录"));
        occurrences.state(run, clock.instant());
        if (run.terminal()) {
            schedules.release(run.enterpriseId(), occurrence.scheduleId(), occurrence.id(), clock.instant());
        }
    }

    public void pause(ScheduleRecord schedule, String reason) {
        schedules.advance(schedule, null, reason, clock.instant());
        notifications.enqueue(schedule.enterpriseId(), schedule.userId(),
            new Notice("schedule:" + schedule.id() + ":pause:" + schedule.revision(), "schedule",
                "“" + schedule.name() + "”已暂停", views.reason(reason), "schedule", schedule.id()));
    }
}

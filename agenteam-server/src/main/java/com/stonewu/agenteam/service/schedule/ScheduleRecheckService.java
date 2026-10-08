package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.schedule.ScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成员、资源或雇佣变更已锁企业；受影响计划在同一事务暂停并排入本人提醒。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ScheduleRecheckService {
    private final ScheduleMapper schedules;
    private final ScheduleAccessService access;
    private final ScheduleRunStateService states;

    public ScheduleRecheckService(ScheduleMapper schedules, ScheduleAccessService access,
                                  ScheduleRunStateService states) {
        this.schedules = schedules;
        this.access = access;
        this.states = states;
    }

    public void enterprise(String enterprise) {
        for (var schedule : schedules.enabledForEnterprise(enterprise)) {
            var checked = access.check(schedule);
            if (checked.reason() != null) {
                states.pause(schedule, checked.reason());
            }
        }
    }
}

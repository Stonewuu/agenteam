package com.stonewu.agenteam.service.schedule;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.schedule.entity.ScheduledOccurrenceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 每项操作自行汇总后续工作，统一在事务中保存状态并释放已结束的计划。 */
@Service
public class ScheduleActionReconciler {
    private final EnterpriseMapper enterprises;
    private final ScheduleActionStore store;
    private final ScheduleActionRegistry actions;

    public ScheduleActionReconciler(EnterpriseMapper enterprises, ScheduleActionStore store, ScheduleActionRegistry actions) {
        this.enterprises = enterprises;
        this.store = store;
        this.actions = actions;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reconcile(ScheduledOccurrenceRow candidate) {
        enterprises.lockEnterprise(candidate.getEnterpriseId()).orElseThrow();
        if (store.lockPlan(candidate.getEnterpriseId(), candidate.getScheduleId()) == null) {
            return;
        }
        var row = store.lock(candidate.getEnterpriseId(), candidate.getScheduleId(), candidate.getId());
        if (row == null || !ScheduleActionStore.ACTIVE.contains(row.getStatus())) {
            return;
        }
        if (store.prepared(row)) {
            actions.require(row.getActionType(), row.getActionSchemaVersion()).reconcile(row).ifPresent(outcome -> store.apply(row, outcome));
        } else {
            // 尚未准备的记录也更新检查时间，防止扫描始终停留在同一批等待项。
            store.save(row);
        }
    }
}

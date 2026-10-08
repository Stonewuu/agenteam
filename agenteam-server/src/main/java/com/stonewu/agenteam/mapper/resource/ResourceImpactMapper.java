package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.schedule.ScheduleSqlMapper;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.response.ResourceImpactView.Dependency;
import com.stonewu.agenteam.model.schedule.entity.ScheduledTaskRow;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 发布依赖和当前雇佣从实际关系表读取，摘要只返回调用者允许查看的资源。
 */
@Repository
public class ResourceImpactMapper {
    private final ResourceImpactSqlMapper statements;
    private final ScheduleSqlMapper schedules;

    public ResourceImpactMapper(ResourceImpactSqlMapper statements, ScheduleSqlMapper schedules) {
        this.statements = statements;
        this.schedules = schedules;
    }

    public long dependents(String enterprise, String id) {
        return DataAccessUtils.nullableSingleResult(statements.dependentsResourceDependency(enterprise, id));
    }

    public List<Dependency> visible(String enterprise, String id, ResourceQueryScope scope, int limit) {
        if (limit == 0) {
            return List.of();
        }
        return statements.visibleDependencies(enterprise, id, scope, limit).stream()
            .map(rows -> new Dependency(rows.getId(), rows.getKind(), rows.getName())).toList();
    }

    public long activeHires(String enterprise, String id) {
        return DataAccessUtils.nullableSingleResult(statements.activeHiresAgentHire(enterprise, id));
    }

    /**
     * 逐层查找使用此资源的固定版本；相同版本只查一次，避免重复路径放大计划数量。
     */
    public long enabledSchedules(String enterprise, String id) {
        return DataAccessUtils.nullableSingleResult(statements.enabledSchedulesResourceVersion(enterprise, id));
    }

    public long activeRuns(String enterprise, String id) {
        return DataAccessUtils.nullableSingleResult(statements.activeRunsAgentRun(enterprise, id));
    }

    /**
     * 删除事务已经锁定企业，查询和暂停使用同一批当前启用的计划。
     */
    public int pauseSchedules(String enterprise, String resourceId, Instant now) {
        var ids = statements.enabledScheduleIds(enterprise, resourceId);
        if (ids.isEmpty()) {
            return 0;
        }
        int changed = schedules.update(new LambdaUpdateWrapper<ScheduledTaskRow>()
            .eq(ScheduledTaskRow::getEnterpriseId, enterprise).in(ScheduledTaskRow::getId, ids)
            .eq(ScheduledTaskRow::getEnabled, true).isNull(ScheduledTaskRow::getDeletedAt)
            .set(ScheduledTaskRow::getEnabled, false).set(ScheduledTaskRow::getNextRunAt, null)
            .set(ScheduledTaskRow::getPauseReason, "SCHEDULE_RESOURCE_UNAVAILABLE")
            .set(ScheduledTaskRow::getLastCheckedAt, now).set(ScheduledTaskRow::getUpdatedAt, now)
            .setIncrBy(ScheduledTaskRow::getRevision, 1));
        if (changed != ids.size()) {
            throw new IllegalStateException("删除资源时，关联计划的暂停结果与预期不一致");
        }
        return changed;
    }
}

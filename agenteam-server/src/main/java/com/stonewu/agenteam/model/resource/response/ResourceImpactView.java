package com.stonewu.agenteam.model.resource.response;

import java.util.List;

/**
 * 引用、雇佣、已启用计划和未结束执行均从实际关系计算，不开放私有计划正文。
 */
public record ResourceImpactView(String resourceId, String revision, List<Dependency> visibleDependencies,
                                 long hiddenDependencyCount, long activeHireCount, long enabledScheduleCount,
                                 long activeRunCount, boolean canDelete) {
    public record Dependency(String id, String kind, String name) {
    }
}

package com.stonewu.agenteam.mapper.resource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.ResourceImpactQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ResourceImpactMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ResourceImpactSqlMapper extends MPJBaseMapper<AgentHireRow> {
    List<ResourceImpactQueryRow> visibleDependencies(@Param("enterprise") String enterprise, @Param("id") String id,
                                                     @Param("scope") ResourceQueryScope scope,
                                                     @Param("limit") int limit);

    List<Long> dependentsResourceDependency(@Param("enterprise") String enterprise, @Param("id") String id);

    default List<Long> activeHiresAgentHire(String enterprise, String id) {
        return List.of(selectCount(new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise)
            .eq(AgentHireRow::getAgentId, id).eq(AgentHireRow::getStatus, "active")));
    }

    List<Long> enabledSchedulesResourceVersion(@Param("enterprise") String enterprise, @Param("id") String id);

    List<String> enabledScheduleIds(@Param("enterprise") String enterprise, @Param("id") String id);

    List<Long> activeRunsAgentRun(@Param("enterprise") String enterprise, @Param("id") String id);
}

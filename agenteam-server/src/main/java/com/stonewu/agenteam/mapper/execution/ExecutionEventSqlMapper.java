package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.AgentEventRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

/**
 * 仅用于清理旧版本遗留事件，新对话变化不再写入此表。
 */
@Mapper
public interface ExecutionEventSqlMapper extends MPJBaseMapper<AgentEventRow> {
    int removeExpiredAgentEvent(@Param("boundary") Timestamp boundary);

    default int removeAgentEvent(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getEnterpriseId, enterpriseId)
            .eq(AgentEventRow::getRunId, id));
    }
}

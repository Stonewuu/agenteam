package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import com.stonewu.agenteam.model.execution.entity.RunActivityQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * RunActivityMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface RunActivitySqlMapper extends MPJBaseMapper<AgentRunRow> {
    default List<RunActivityQueryRow> getAgentRun(String enterpriseId, String id) {
        var criteria = new LambdaQueryWrapper<AgentRunRow>().select(AgentRunRow::getUsedSteps,
                AgentRunRow::getCountedToolsJson, AgentRunRow::getActiveMillis, AgentRunRow::getActiveSegmentStartedAt,
                AgentRunRow::getExecutionPhase, AgentRunRow::getQueuedAt).eq(AgentRunRow::getEnterpriseId, enterpriseId)
            .eq(AgentRunRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new RunActivityQueryRow();
            if (storedRow.getUsedSteps() != null) {
                mappedRow.setUsedSteps(storedRow.getUsedSteps());
            }
            if (storedRow.getCountedToolsJson() != null) {
                mappedRow.setCountedToolsJson(storedRow.getCountedToolsJson());
            }
            if (storedRow.getActiveMillis() != null) {
                mappedRow.setActiveMillis(storedRow.getActiveMillis());
            }
            if (storedRow.getActiveSegmentStartedAt() != null) {
                mappedRow.setActiveSegmentStartedAt(
                    (storedRow.getActiveSegmentStartedAt() == null ? null : Timestamp.from(
                        storedRow.getActiveSegmentStartedAt())));
            }
            if (storedRow.getExecutionPhase() != null) {
                mappedRow.setExecutionPhase(storedRow.getExecutionPhase());
            }
            if (storedRow.getQueuedAt() != null) {
                mappedRow.setQueuedAt(
                    (storedRow.getQueuedAt() == null ? null : Timestamp.from(storedRow.getQueuedAt())));
            }
            return mappedRow;
        }).toList();
    }

    default int reserveAgentRun(int used, String countedToolsJson, String phase, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterpriseId)
            .eq(AgentRunRow::getId, id).set(AgentRunRow::getUsedSteps, used)
            .set(AgentRunRow::getCountedToolsJson, countedToolsJson).set(AgentRunRow::getExecutionPhase, phase));
    }

    int pauseAgentRun(@Param("status") String status, @Param("now") Timestamp now,
                      @Param("enterpriseId") String enterpriseId, @Param("id") String id);


    int queueAgentRun(@Param("now") Timestamp now, @Param("enterpriseId") String enterpriseId, @Param("id") String id);


    default int phaseAgentRun(String executionPhase, String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterpriseId)
            .eq(AgentRunRow::getId, id).set(AgentRunRow::getExecutionPhase, executionPhase));
    }

    default int stepErrorsAgentRun(String enterpriseId, String id) {
        return update(new LambdaUpdateWrapper<AgentRunRow>().eq(AgentRunRow::getEnterpriseId, enterpriseId)
            .eq(AgentRunRow::getId, id).set(AgentRunRow::getHasStepErrors, true));
    }
}

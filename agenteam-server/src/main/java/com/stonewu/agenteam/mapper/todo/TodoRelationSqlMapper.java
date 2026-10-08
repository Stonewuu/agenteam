package com.stonewu.agenteam.mapper.todo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.RunStepRow;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * TodoRelationMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface TodoRelationSqlMapper extends MPJBaseMapper<RunStepRow> {


    default List<Boolean> hasStartedWorkflowRunStep(String enterprise, String run) {
        return List.of(exists(new LambdaQueryWrapper<RunStepRow>().eq(RunStepRow::getEnterpriseId, enterprise)
            .eq(RunStepRow::getRunId, run).isNotNull(RunStepRow::getWorkflowJson).isNotNull(RunStepRow::getStartedAt)));
    }
}

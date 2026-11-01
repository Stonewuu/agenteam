package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface RunQuestionMapper extends BaseMapper<RunQuestionRow> {
    default Optional<RunQuestionRow> find(String enterprise, String id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<RunQuestionRow>()
            .eq(RunQuestionRow::getEnterpriseId, enterprise).eq(RunQuestionRow::getId, id)));
    }

    default Optional<RunQuestionRow> forTool(String enterprise, String toolCall) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<RunQuestionRow>()
            .eq(RunQuestionRow::getEnterpriseId, enterprise).eq(RunQuestionRow::getToolCallId, toolCall)));
    }

    default List<RunQuestionRow> forRun(String enterprise, String run) {
        return selectList(new LambdaQueryWrapper<RunQuestionRow>().eq(RunQuestionRow::getEnterpriseId, enterprise)
            .eq(RunQuestionRow::getRunId, run).orderByAsc(RunQuestionRow::getCreatedAt, RunQuestionRow::getId));
    }

    default boolean pending(String enterprise, String run) {
        return exists(new LambdaQueryWrapper<RunQuestionRow>().eq(RunQuestionRow::getEnterpriseId, enterprise)
            .eq(RunQuestionRow::getRunId, run).eq(RunQuestionRow::getStatus, "pending"));
    }
}

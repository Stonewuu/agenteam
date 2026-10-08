package com.stonewu.agenteam.mapper.test.tool;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.tool.entity.ToolCallRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * tool_call 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface ToolCallFixtureMapper extends MPJBaseMapper<ToolCallRow> {
    List<Integer> scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject(@Param("args") Object... args);

    List<Integer> scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject16(@Param("args") Object... args);

    List<Integer> scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject17(@Param("args") Object... args);

    List<Integer> scheduleRetryApiRetryReexecutesReadOnlyToolsWithTheSameProviderCallIdWithoutReusingFailedAttemptStateObject18(@Param("args") Object... args);

    List<Integer> workflowExecutionApiParallelNodeAgentsResumeTheirOwnToolConfirmationsWithTheSameFrameworkCallIdentifierObject(@Param("args") Object... args);

}

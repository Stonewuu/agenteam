package com.stonewu.agenteam.mapper.test.execution;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.AgentRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * agent_run 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface AgentRunFixtureMapper extends MPJBaseMapper<AgentRunRow> {
    List<String> memberRemovalApiStopRemainingRunsList(@Param("args") Object... args);

    List<Long> scheduleRetryApiAssertDelayObject(@Param("args") Object... args);

}

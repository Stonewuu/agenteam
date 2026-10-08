package com.stonewu.agenteam.mapper.test.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * agent_conversation 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface AgentConversationFixtureMapper extends MPJBaseMapper<AgentConversationRow> {
    default int workspaceApiSearchReturnsPublicEmployeeAndPrivateConversationWithoutInternalInstructionsUpdate(@Param("args") Object... args) {
        Instant databaseNow = Instant.now();
        return update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getId, args[0]).set(AgentConversationRow::getStatus, "deleted").set(AgentConversationRow::getDeletedAt, databaseNow));
    }

}

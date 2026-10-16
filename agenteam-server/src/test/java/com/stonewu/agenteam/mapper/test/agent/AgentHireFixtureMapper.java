package com.stonewu.agenteam.mapper.test.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

/** 雇佣记录的测试数据准备。 */
@Mapper
public interface AgentHireFixtureMapper extends MPJBaseMapper<AgentHireRow> {
    default int pauseAgentHires(String enterprise, String agent) {
        return update(new LambdaUpdateWrapper<AgentHireRow>()
            .eq(AgentHireRow::getEnterpriseId, enterprise).eq(AgentHireRow::getAgentId, agent)
            .set(AgentHireRow::getStatus, "paused").set(AgentHireRow::getLastUsedAt, Instant.now()));
    }

    default int insertHire(String id, String enterprise, String user, String agent, Instant hiredAt) {
        var row = new AgentHireRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setUserId(user);
        row.setAgentId(agent);
        row.setHiredAt(hiredAt);
        return insert(row);
    }
}

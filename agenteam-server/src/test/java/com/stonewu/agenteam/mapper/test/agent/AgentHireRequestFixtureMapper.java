package com.stonewu.agenteam.mapper.test.agent;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireRequestRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

/**
 * agent_hire_request 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface AgentHireRequestFixtureMapper extends MPJBaseMapper<AgentHireRequestRow> {
    default int createApplication(String id, String status, Integer marker) {
        var row = new AgentHireRequestRow();
        row.setId(id);
        row.setEnterpriseId("first");
        row.setUserId("owner");
        row.setAgentId("first");
        row.setStatus(status);
        row.setPendingMarker(marker);
        row.setExpiresAt(Instant.now().plusSeconds(7 * 86400));
        return insert(row);
    }

}

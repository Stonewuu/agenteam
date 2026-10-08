package com.stonewu.agenteam.mapper.agent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stonewu.agenteam.model.agent.entity.AgentListingRow;
import com.stonewu.agenteam.model.agent.response.AgentListingView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 未配置上架记录的智能体尚未进入广场，默认可自行雇佣。
 */
@Mapper
public interface AgentListingMapper extends BaseMapper<AgentListingRow> {

    default AgentListingView find(String enterpriseId, String agentId) {
        var row = selectOne(Wrappers.<AgentListingRow>lambdaQuery().eq(AgentListingRow::getEnterpriseId, enterpriseId)
            .eq(AgentListingRow::getAgentId, agentId));
        return row == null ? new AgentListingView(false, "automatic") : view(row);
    }

    default Map<String, AgentListingView> findMany(String enterpriseId, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, AgentListingView> result = new LinkedHashMap<>();
        for (var row : selectList(
            Wrappers.<AgentListingRow>lambdaQuery().eq(AgentListingRow::getEnterpriseId, enterpriseId)
                .in(AgentListingRow::getAgentId, ids))) {
            result.put(row.getAgentId(), view(row));
        }
        return result;
    }

    void save(@Param("enterpriseId") String enterpriseId, @Param("agentId") String agentId,
              @Param("listed") boolean listed, @Param("hirePolicy") String hirePolicy, @Param("actor") String actor,
              @Param("now") Instant now);

    default void unlist(String enterpriseId, String agentId, String actor, Instant now) {
        update(Wrappers.<AgentListingRow>lambdaUpdate().eq(AgentListingRow::getEnterpriseId, enterpriseId)
            .eq(AgentListingRow::getAgentId, agentId).set(AgentListingRow::getListed, 0)
            .set(AgentListingRow::getUpdatedBy, actor).set(AgentListingRow::getUpdatedAt, now));
    }

    private AgentListingView view(AgentListingRow row) {
        return new AgentListingView(row.getListed() != 0, row.getHirePolicy());
    }
}

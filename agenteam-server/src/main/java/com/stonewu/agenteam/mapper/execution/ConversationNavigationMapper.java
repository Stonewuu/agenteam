package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.entity.ConversationNavigationRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** 按任务合并后进行筛选与分页，防止岗位对话占满一页或跨页重复。 */
@Mapper
public interface ConversationNavigationMapper {
    List<ConversationNavigationRow> list(@Param("enterprise") String enterprise, @Param("user") String user,
        @Param("states") List<String> states, @Param("favorite") Boolean favorite, @Param("agentId") String agentId,
        @Param("query") String query, @Param("scope") String scope, @Param("after") PagePosition after,
        @Param("limit") int limit, @Param("oldestDeleted") Instant oldestDeleted);
}

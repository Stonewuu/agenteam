package com.stonewu.agenteam.mapper.workspace;

import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.workspace.entity.WorkspaceSearchQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * WorkspaceSearchMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface WorkspaceSearchSqlMapper {
    List<WorkspaceSearchQueryRow> searchEmployees(@Param("enterprise") String enterprise, @Param("user") String user,
                                                  @Param("query") String query, @Param("mine") boolean mine,
                                                  @Param("market") ResourceQueryScope market);

    List<WorkspaceSearchQueryRow> searchResources(@Param("scopes") List<ResourceQueryScope> scopes,
                                                  @Param("query") String query);

    List<WorkspaceSearchQueryRow> conversationsAgentConversation(@Param("enterprise") String enterprise,
                                                                 @Param("user") String user,
                                                                 @Param("query") String query);
}

package com.stonewu.agenteam.mapper.resource;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.UsableVersionQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * UsableVersionMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface UsableVersionSqlMapper {
    List<UsableVersionQueryRow> listVersions(@Param("scope") ResourceQueryScope scope, @Param("query") String query,
                                             @Param("cursor") PagePosition cursor, @Param("limit") int limit,
                                             @Param("subagentsOnly") boolean subagentsOnly,
                                             @Param("resourceId") String resourceId,
                                             @Param("latestOnly") boolean latestOnly);

    List<UsableVersionQueryRow> selectedVersions(@Param("scope") ResourceQueryScope scope,
                                                 @Param("ids") List<String> ids);
}

package com.stonewu.agenteam.mapper.tool;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.entity.QueryTimeRange;
import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import com.stonewu.agenteam.model.tool.entity.ToolLogQueryRow;
import com.stonewu.agenteam.model.tool.request.ToolLogQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ToolLogMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ToolLogSqlMapper {
    List<ToolLogQueryRow> listCalls(@Param("enterprise") String enterprise,
                                    @Param("scopes") List<OwnerQueryScope> scopes,
                                    @Param("detailScope") OwnerQueryScope detailScope,
                                    @Param("filter") ToolLogQuery filter, @Param("range") QueryTimeRange range,
                                    @Param("cursor") PagePosition cursor, @Param("limit") int limit);

    List<ToolLogQueryRow> callDetails(@Param("enterprise") String enterprise,
                                      @Param("viewScope") OwnerQueryScope viewScope,
                                      @Param("detailScope") OwnerQueryScope detailScope, @Param("id") String id);
}

package com.stonewu.agenteam.mapper.data.mysql;

import com.stonewu.agenteam.model.data.entity.DataQueryBudget;
import com.stonewu.agenteam.model.data.entity.DataQueryParameters;
import com.stonewu.agenteam.model.data.entity.MysqlColumnRow;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;
import java.util.Map;

/**
 * 只绑定单次外部连接，不注册为平台数据库的 Spring 映射实例。
 */
public interface ExternalMysqlSqlMapper {
    void readGrants(@Param("queryBudget") DataQueryBudget queryBudget, ResultHandler<String> handler);

    List<String> findTables(@Param("database") String database, @Param("table") String table,
                            @Param("queryBudget") DataQueryBudget queryBudget);

    List<MysqlColumnRow> readColumns(@Param("database") String database, @Param("table") String table,
                                     @Param("queryBudget") DataQueryBudget queryBudget);

    void readRows(@Param("query") DataQueryParameters query, @Param("queryBudget") DataQueryBudget queryBudget,
                  @Param("byteBudget") int byteBudget, @Param("control") ToolCallControl control,
                  ResultHandler<Map<String, Object>> handler);
}

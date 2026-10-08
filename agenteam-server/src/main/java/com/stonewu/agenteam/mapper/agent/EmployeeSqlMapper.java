package com.stonewu.agenteam.mapper.agent;

import com.stonewu.agenteam.model.agent.entity.EmployeeQueryRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * EmployeeMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface EmployeeSqlMapper {

    List<EmployeeQueryRow> listEmployees(@Param("enterprise") String enterprise, @Param("user") String user,
                                         @Param("mine") boolean mine, @Param("market") ResourceQueryScope market,
                                         @Param("query") String query, @Param("tags") List<String> tags,
                                         @Param("cursor") PagePosition cursor, @Param("limit") int limit,
                                         @Param("now") Instant now);

    List<EmployeeQueryRow> findEmployee(@Param("enterprise") String enterprise, @Param("user") String user,
                                        @Param("id") String id, @Param("market") ResourceQueryScope market,
                                        @Param("now") Instant now);

    List<EmployeeQueryRow> recentEmployees(@Param("user") String user, @Param("usage") ResourceQueryScope usage,
                                           @Param("now") Instant now);
}

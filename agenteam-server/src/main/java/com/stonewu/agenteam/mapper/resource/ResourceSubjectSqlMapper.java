package com.stonewu.agenteam.mapper.resource;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.resource.entity.ResourceSubjectQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ResourceSubjectMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ResourceSubjectSqlMapper {
    List<ResourceSubjectQueryRow> listSubjects(@Param("enterprise") String enterprise,
                                               @Param("query") String query,
                                               @Param("ownerPermission") String ownerPermission,
                                               @Param("selected") List<String> selected,
                                               @Param("cursor") PagePosition cursor, @Param("limit") int limit);
}

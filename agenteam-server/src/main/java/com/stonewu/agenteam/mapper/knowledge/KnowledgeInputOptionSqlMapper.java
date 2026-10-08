package com.stonewu.agenteam.mapper.knowledge;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.knowledge.entity.KnowledgeInputOptionQueryRow;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * KnowledgeInputOptionMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface KnowledgeInputOptionSqlMapper {
    List<KnowledgeInputOptionQueryRow> listDocuments(@Param("scope") ResourceQueryScope scope,
                                                     @Param("resourceIds") List<String> resourceIds,
                                                     @Param("query") String query, @Param("cursor") PagePosition cursor,
                                                     @Param("limit") int limit);
}

package com.stonewu.agenteam.mapper.export;

import com.stonewu.agenteam.model.permission.entity.OwnerQueryScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * ExportAccessMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ExportAccessSqlMapper {
    List<String> visibleActors(@Param("scope") OwnerQueryScope scope, @Param("actors") List<String> actors);
}

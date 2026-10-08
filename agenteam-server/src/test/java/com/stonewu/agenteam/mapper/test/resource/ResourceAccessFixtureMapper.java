package com.stonewu.agenteam.mapper.test.resource;

import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 使用生产权限条件验证先筛选资格、再限制结果数量。
 */
@Mapper
public interface ResourceAccessFixtureMapper {
    List<String> visibleIds(@Param("scope") ResourceQueryScope scope, @Param("limit") int limit);
}

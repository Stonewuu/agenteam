package com.stonewu.agenteam.mapper.enterprise;

import com.stonewu.agenteam.model.enterprise.entity.OrganizationDependencyCounts;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * 每次查询同时返回全部引用数量，避免逐项访问数据库。
 */
@Mapper
public interface OrganizationDependencySqlMapper {
    OrganizationDependencyCounts role(@Param("enterprise") String enterprise, @Param("role") String role,
                                      @Param("now") Instant now);

    OrganizationDependencyCounts team(@Param("enterprise") String enterprise, @Param("team") String team,
                                      @Param("now") Instant now);
}

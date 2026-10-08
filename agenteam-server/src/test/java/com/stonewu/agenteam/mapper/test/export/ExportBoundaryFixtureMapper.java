package com.stonewu.agenteam.mapper.test.export;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * 在数据库内生成十万行数据，验证导出上限与后续拒绝行为。
 */
@Mapper
public interface ExportBoundaryFixtureMapper {
    void seedAuditRows(@Param("enterprise") String enterprise, @Param("actor") String actor,
                       @Param("action") String action, @Param("now") Instant now);
}

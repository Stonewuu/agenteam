package com.stonewu.agenteam.mapper.test.resource;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.resource.entity.ResourceDraftRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * resource_draft 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface ResourceDraftFixtureMapper extends MPJBaseMapper<ResourceDraftRow> {
    int pluginCheckApiExpiredEvidenceAndChangedSelectedToolRequireAnotherReviewUpdate(@Param("args") Object... args);

}

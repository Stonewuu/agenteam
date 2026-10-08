package com.stonewu.agenteam.mapper.test.schema;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.test.schema.LegacyDemoAccountRow;
import org.apache.ibatis.annotations.Mapper;

/** 只读校验已发布初始化脚本保留的历史控制记录，不参与生产应用。 */
@Mapper
public interface LegacyDemoAccountMapper extends MPJBaseMapper<LegacyDemoAccountRow> {
}

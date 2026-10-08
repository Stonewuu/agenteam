package com.stonewu.agenteam.mapper.test.usage;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaEntryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * quota_entry 的测试数据准备与实际数据库断言。
 */
@Mapper
public interface QuotaEntryFixtureMapper extends MPJBaseMapper<QuotaEntryRow> {
    List<Integer> platformJourneyFixtureCompleteJourneyObject(@Param("args") Object... args);

}

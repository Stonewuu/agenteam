package com.stonewu.agenteam.mapper.usage;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaEntryRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;

/**
 * quota_entry 表的通用数据库操作。
 */
@Mapper
public interface QuotaEntryTableMapper extends MPJBaseMapper<QuotaEntryRow> {
    default int settleQuotaEntry(String state, Timestamp now, String enterprise, String run, String bucket) {
        return update(new LambdaUpdateWrapper<QuotaEntryRow>().eq(QuotaEntryRow::getEnterpriseId, enterprise)
            .eq(QuotaEntryRow::getRunId, run).eq(QuotaEntryRow::getBucketId, bucket)
            .eq(QuotaEntryRow::getState, "reserved").set(QuotaEntryRow::getState, state)
            .set(QuotaEntryRow::getUpdatedAt, now));
    }
}

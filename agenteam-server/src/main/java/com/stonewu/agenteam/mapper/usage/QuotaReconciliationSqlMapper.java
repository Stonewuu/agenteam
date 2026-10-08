package com.stonewu.agenteam.mapper.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaBucketRow;
import com.stonewu.agenteam.model.usage.entity.QuotaReconciliationQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * QuotaReconciliationMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface QuotaReconciliationSqlMapper extends MPJBaseMapper<QuotaBucketRow> {
    default List<String> enterprisesQuotaBucket() {
        return selectList(new LambdaQueryWrapper<QuotaBucketRow>().select(QuotaBucketRow::getEnterpriseId)
            .groupBy(QuotaBucketRow::getEnterpriseId).orderByAsc(QuotaBucketRow::getEnterpriseId))
            .stream().map(QuotaBucketRow::getEnterpriseId).toList();
    }

    List<QuotaReconciliationQueryRow> differencesQuotaBucket(@Param("enterprise") String enterprise);

    default int repairQuotaBucket(long actualUsed, long actualReserved, Timestamp now, String enterprise,
                                  String bucketId) {
        return update(new LambdaUpdateWrapper<QuotaBucketRow>().eq(QuotaBucketRow::getEnterpriseId, enterprise)
            .eq(QuotaBucketRow::getId, bucketId).set(QuotaBucketRow::getUsedCount, actualUsed)
            .set(QuotaBucketRow::getReservedCount, actualReserved).set(QuotaBucketRow::getUpdatedAt, now));
    }
}

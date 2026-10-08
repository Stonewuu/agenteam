package com.stonewu.agenteam.mapper.usage;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaBucketRow;
import com.stonewu.agenteam.model.usage.entity.QuotaQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * state 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface QuotaSqlMapper extends MPJBaseMapper<QuotaBucketRow> {
    List<QuotaQueryRow> lockEnterpriseEnterprise(@Param("enterprise") String enterprise);


    int bucketQuotaBucket(@Param("value") String value, @Param("enterprise") String enterprise, @Param("id") String id,
                          @Param("time") Timestamp time, @Param("time2") Timestamp time2,
                          @Param("timezone") String timezone, @Param("now") Timestamp now);

    List<QuotaQueryRow> bucketQuotaBucket2(@Param("enterprise") String enterprise, @Param("id") String id,
                                           @Param("time") Timestamp time);

    List<QuotaQueryRow> entriesQuotaEntry(@Param("enterprise") String enterprise, @Param("run") String run);

    int reserveQuotaEntry(@Param("enterprise") String enterprise, @Param("run") String run,
                          @Param("bucket") String bucket, @Param("now") Timestamp now);

    default int reserveQuotaBucket(Timestamp now, String enterprise, String bucket) {
        return update(new LambdaUpdateWrapper<QuotaBucketRow>().eq(QuotaBucketRow::getEnterpriseId, enterprise)
            .eq(QuotaBucketRow::getId, bucket).setIncrBy(QuotaBucketRow::getReservedCount, 1)
            .set(QuotaBucketRow::getUpdatedAt, now));
    }


    default int settleQuotaBucket(int usedCount, Timestamp now, String enterprise, String bucket) {
        return update(new LambdaUpdateWrapper<QuotaBucketRow>().eq(QuotaBucketRow::getEnterpriseId, enterprise)
            .eq(QuotaBucketRow::getId, bucket).setDecrBy(QuotaBucketRow::getReservedCount, 1)
            .setIncrBy(QuotaBucketRow::getUsedCount, usedCount).set(QuotaBucketRow::getUpdatedAt, now));
    }
}

package com.stonewu.agenteam.mapper.modelusage;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.modelusage.entity.ModelUsageRecordRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 一次模型请求的原始用量与固定计价依据，调用方必须附带企业范围。
 */
@Mapper
public interface ModelUsageRecordMapper extends MPJBaseMapper<ModelUsageRecordRow> {
    ModelUsageRecordRow lock(@Param("enterprise") String enterprise, @Param("id") String id);

    List<ModelUsageRecordRow> lockExpired(@Param("now") Instant now);

    List<ModelUsageRecordRow> lockPendingCalculations(@Param("now") Instant now);
}

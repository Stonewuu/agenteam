package com.stonewu.agenteam.mapper.usage;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaPolicyRow;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;

/** 保留计数规则的基础存储和初始化；商业管理查询由商业映射提供。 */
@Mapper
public interface QuotaPolicySqlMapper extends MPJBaseMapper<QuotaPolicyRow> {
    default int createDefaultQuota(String id, String enterprise, Long initialLimit, Instant now) {
        var row = new QuotaPolicyRow();
        row.setId(id);
        row.setEnterpriseId(enterprise);
        row.setSubjectType("enterprise");
        row.setSubjectId(enterprise);
        row.setMonthlyLimit(initialLimit);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return insert(row);
    }
}

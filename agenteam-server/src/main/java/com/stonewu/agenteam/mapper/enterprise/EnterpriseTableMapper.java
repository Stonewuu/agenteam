package com.stonewu.agenteam.mapper.enterprise;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import org.apache.ibatis.annotations.Mapper;

import java.sql.Timestamp;

/**
 * 企业表的基础访问，业务写入仍由企业服务编排。
 */
@Mapper
public interface EnterpriseTableMapper extends MPJBaseMapper<EnterpriseRow> {
    default int enterpriseEnterprise(String name, String description, String contactEmail, String timezone,
                                     String pendingQuotaTimezone, Integer retentionDays, Timestamp now, String id,
                                     long revision) {
        return update(new LambdaUpdateWrapper<EnterpriseRow>().eq(EnterpriseRow::getId, id)
            .eq(EnterpriseRow::getRevision, revision).set(EnterpriseRow::getName, name)
            .set(EnterpriseRow::getDescription, description).set(EnterpriseRow::getContactEmail, contactEmail)
            .set(EnterpriseRow::getTimezone, timezone).set(EnterpriseRow::getPendingQuotaTimezone, pendingQuotaTimezone)
            .set(EnterpriseRow::getRetentionDays, retentionDays).setIncrBy(EnterpriseRow::getRevision, 1)
            .set(EnterpriseRow::getUpdatedAt, now));
    }

    default int periodEnterprise(String quotaTimezone, String pendingTimezone, Timestamp quotaPeriodStart,
                                 Timestamp time2, String enterprise) {
        return update(new LambdaUpdateWrapper<EnterpriseRow>().eq(EnterpriseRow::getId, enterprise)
            .set(EnterpriseRow::getQuotaTimezone, quotaTimezone)
            .set(EnterpriseRow::getPendingQuotaTimezone, pendingTimezone)
            .set(EnterpriseRow::getQuotaPeriodStart, quotaPeriodStart).set(EnterpriseRow::getQuotaPeriodEnd, time2));
    }
}

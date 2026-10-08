package com.stonewu.agenteam.mapper.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseTableMapper;
import com.stonewu.agenteam.model.usage.entity.QuotaBucketRow;
import com.stonewu.agenteam.model.usage.entity.QuotaPolicyRow;
import com.stonewu.agenteam.model.usage.response.UsageView;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** 社区只读取企业自身的计数记录，不把组织规则作为公共执行限制。 */
@Repository
public class EnterpriseUsageMapper {
    private final QuotaPolicySqlMapper policies;
    private final QuotaSqlMapper buckets;
    private final EnterpriseTableMapper enterprises;

    public EnterpriseUsageMapper(QuotaPolicySqlMapper policies, QuotaSqlMapper buckets,
                                  EnterpriseTableMapper enterprises) {
        this.policies = policies;
        this.buckets = buckets;
        this.enterprises = enterprises;
    }

    public QuotaMapper.Policy policy(String enterprise) {
        var row = policies.selectOne(new LambdaQueryWrapper<QuotaPolicyRow>()
            .eq(QuotaPolicyRow::getEnterpriseId, enterprise).eq(QuotaPolicyRow::getSubjectType, "enterprise")
            .eq(QuotaPolicyRow::getSubjectId, enterprise));
        if (row == null) {
            throw new IllegalStateException("企业缺少用于记录实际用量的次数规则");
        }
        return new QuotaMapper.Policy(row.getId(), "enterprise", enterprise, null);
    }

    public UsageView.Item usage(String enterprise, Instant periodStart) {
        var policy = policy(enterprise);
        var owner = enterprises.selectById(enterprise);
        if (owner == null) {
            throw new IllegalStateException("无法读取不存在企业的实际用量");
        }
        var bucket = buckets.selectOne(new LambdaQueryWrapper<QuotaBucketRow>()
            .eq(QuotaBucketRow::getEnterpriseId, enterprise).eq(QuotaBucketRow::getPolicyId, policy.id())
            .eq(QuotaBucketRow::getPeriodStart, periodStart));
        return new UsageView.Item(policy.id(), "enterprise", owner.getName(),
            bucket == null ? 0 : bucket.getUsedCount(), bucket == null ? 0 : bucket.getReservedCount(), null, null);
    }
}

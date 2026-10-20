package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.mapper.usage.EnterpriseUsageMapper;
import com.stonewu.agenteam.mapper.usage.QuotaMapper;
import com.stonewu.agenteam.model.usage.response.UsageView;

import java.time.Instant;
import java.util.List;

/** 记录企业实际执行次数，不设置执行次数上限。 */
public class CommunityQuotaPolicyProvider implements QuotaPolicyProvider {
    private final EnterpriseUsageMapper usage;

    public CommunityQuotaPolicyProvider(EnterpriseUsageMapper usage) {
        this.usage = usage;
    }

    @Override
    public Long initialEnterpriseLimit() {
        return null;
    }

    @Override
    public List<QuotaMapper.Policy> policies(String enterprise, String user) {
        return List.of(usage.policy(enterprise));
    }

    @Override
    public List<UsageView.Item> usage(String enterprise, Instant periodStart) {
        return List.of(usage.usage(enterprise, periodStart));
    }
}

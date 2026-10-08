package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.mapper.usage.QuotaMapper;
import com.stonewu.agenteam.model.usage.response.UsageView;

import java.time.Instant;
import java.util.List;

/** 发行组件提供适用规则；公共执行服务统一完成预留、扣减和释放。 */
public interface QuotaPolicyProvider {
    Long initialEnterpriseLimit();

    List<QuotaMapper.Policy> policies(String enterprise, String user);

    List<UsageView.Item> usage(String enterprise, Instant periodStart);
}

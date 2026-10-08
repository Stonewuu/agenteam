package com.stonewu.agenteam.service.usage;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.usage.response.UsageView;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 两版共用用量读取权限和周期处理，具体计数范围由发行组件提供。 */
@Service
public class UsageQueryService {
    private final QuotaPolicyProvider policies;
    private final QuotaPeriodService periods;
    private final EnterpriseAuthorizationService authorization;

    public UsageQueryService(QuotaPolicyProvider policies, QuotaPeriodService periods,
                              EnterpriseAuthorizationService authorization) {
        this.policies = policies;
        this.periods = periods;
        this.authorization = authorization;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UsageView usage(AuthContext actor) {
        authorization.requireEnterpriseScope(authorization.lockAndRequire(actor, "usage.view"));
        var period = periods.current(actor.enterpriseId());
        return new UsageView(period.start().toString(), period.end().toString(), period.timezone(),
            policies.usage(actor.enterpriseId(), period.start()));
    }
}

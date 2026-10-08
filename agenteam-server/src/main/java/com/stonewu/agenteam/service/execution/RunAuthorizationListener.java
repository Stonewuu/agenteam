package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.permission.entity.EnterprisePermissionChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授权变更与不再允许执行的任务停止一起提交，不依赖下一次浏览器请求。
 */
@Component
public class RunAuthorizationListener {
    private final RunLifecycleService executions;

    public RunAuthorizationListener(RunLifecycleService executions) {
        this.executions = executions;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(EnterprisePermissionChanged event) {
        executions.recheckEnterprise(event.enterpriseId());
    }
}

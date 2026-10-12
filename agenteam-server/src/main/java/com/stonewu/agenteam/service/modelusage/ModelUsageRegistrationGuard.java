package com.stonewu.agenteam.service.modelusage;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import org.springframework.stereotype.Component;

import java.util.concurrent.CancellationException;

/**
 * 发送前与账号清理共用用户锁，避免已经取消或清理的旧执行登记新的请求。
 */
@Component
public class ModelUsageRegistrationGuard {
    private final AuthMapper users;
    private final RunMapper runs;
    private final AccountBehaviorService behavior;

    public ModelUsageRegistrationGuard(AuthMapper users, RunMapper runs, AccountBehaviorService behavior) {
        this.users = users;
        this.runs = runs;
        this.behavior = behavior;
    }

    public void requireActive(ModelCallContext context) {
        var user = users.findByIdForUpdate(context.userId(), 2).orElseThrow(() -> new CancellationException("模型调用账号已不可用。"));
        if (!user.status().equals("active") || !behavior.allowed(user)) {
            throw new CancellationException("模型调用账号已不可用。");
        }
        if (context.runId() == null && context.purpose().equals("preview")) {
            return;
        }
        var run = runs.find(context.enterpriseId(), context.runId(), false).orElseThrow(() -> new CancellationException("本次执行已不存在。"));
        if (!run.userId().equals(context.userId()) || !run.status().equals("running")
            || run.currentAttemptNo() != context.attemptNo() || run.leaseVersion() != context.leaseVersion()) {
            throw new CancellationException("本次执行已结束或已由另一次尝试继续。");
        }
    }
}

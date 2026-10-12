package com.stonewu.agenteam.service.plugin.javaworker;

import com.stonewu.tool.api.ToolCancellation;
import com.stonewu.tool.api.ToolContext;

import java.time.Duration;

/**
 * 第一次执行登记提交，后续业务请求只重新校验本次调用的授权。
 */
public final class JavaPluginInvocationContext implements ToolContext {
    private final ToolContext original;
    private final Runnable recheck;
    private boolean submitted;

    public JavaPluginInvocationContext(ToolContext original, Runnable recheck) {
        this.original = original;
        this.recheck = recheck;
    }

    @Override
    public String enterpriseId() {
        return original.enterpriseId();
    }

    @Override
    public String actorId() {
        return original.actorId();
    }

    @Override
    public String runId() {
        return original.runId();
    }

    @Override
    public String operationId() {
        return original.operationId();
    }

    @Override
    public Duration timeout() {
        return original.timeout();
    }

    @Override
    public ToolCancellation cancellation() {
        return original.cancellation();
    }

    @Override
    public void beforeSend() {
        cancellation().requireActive();
        if (submitted) {
            recheck.run();
        } else {
            original.beforeSend();
            submitted = true;
        }
    }
}

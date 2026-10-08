package com.stonewu.agenteam.service.tool.provider;

import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.tool.api.ToolContext;

import java.time.Duration;

/**
 * 宿主执行适配上下文；扩展通过公共接口读取身份及取消信号。
 */
public final class ToolProviderContext implements ToolContext {
    private final String enterpriseId;
    private final Duration timeout;
    private final Duration remaining;
    private final ToolCallControl control;
    private final RunRecord run;
    private final JobLease lease;
    private final ExecutionToolBinding binding;
    private final ToolCallRecord call;
    private boolean persisted;

    public ToolProviderContext(String enterpriseId, Duration timeout, ToolCallControl control) {
        this(enterpriseId, timeout, timeout, control, null, null, null, null);
    }

    public ToolProviderContext(String enterpriseId, Duration timeout, Duration remaining, ToolCallControl control,
                               RunRecord run, JobLease lease, ExecutionToolBinding binding, ToolCallRecord call) {
        this.enterpriseId = enterpriseId;
        this.timeout = timeout;
        this.remaining = remaining;
        this.control = control;
        this.run = run;
        this.lease = lease;
        this.binding = binding;
        this.call = call;
    }

    @Override
    public String enterpriseId() {
        return enterpriseId;
    }

    @Override
    public String actorId() {
        return run == null ? null : run.userId();
    }

    @Override
    public String runId() {
        return run == null ? null : run.id();
    }

    @Override
    public String operationId() {
        return call == null ? null : call.operationId();
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public ToolCallControl cancellation() {
        return control;
    }

    @Override
    public void beforeSend() {
        control.beforeSend();
    }

    public RunRecord run() {
        return run;
    }

    public JobLease lease() {
        return lease;
    }

    public ExecutionToolBinding binding() {
        return binding;
    }

    public ToolCallRecord call() {
        return call;
    }

    public Duration remaining() {
        return remaining;
    }

    public boolean persisted() {
        return persisted;
    }

    public void markPersisted() {
        persisted = true;
    }
}

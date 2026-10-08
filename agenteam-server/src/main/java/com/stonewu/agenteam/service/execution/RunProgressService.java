package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.RunActivityMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 节点结果、公开消息和恢复进度一起提交，不能只保存完成消息却在恢复时重复执行节点。
 */
@Service
public class RunProgressService {
    private final RunCheckpointService checkpoints;
    private final ExecutionMessageWriter writer;
    private final RunLifecycleService lifecycle;
    private final RunActivityMapper activity;
    private final ToolCallTransactions tools;

    public RunProgressService(RunCheckpointService checkpoints, ExecutionMessageWriter writer,
                              RunLifecycleService lifecycle, RunActivityMapper activity, ToolCallTransactions tools) {
        this.checkpoints = checkpoints;
        this.writer = writer;
        this.lifecycle = lifecycle;
        this.activity = activity;
        this.tools = tools;
    }

    @Transactional
    public void save(JobLease lease, RunCheckpointService.Prepared checkpoint, String batch,
                     List<ExecutionChange> changes, String phase, boolean stepErrors) {
        var run = lifecycle.locked(lease.enterpriseId(), lease.runId());
        lifecycle.requireLease(run, lease);
        writer.save(lease, batch, changes);
        checkpoints.save(lease, checkpoint);
        activity.phase(run, phase);
        if (stepErrors) {
            activity.stepErrors(run);
        }
    }

    @Transactional
    public void boundary(JobLease lease, RunCheckpointService.Prepared checkpoint, List<String> tools, int modelCost,
                         String phase) {
        lifecycle.beforeExternalStep(lease);
        checkpoints.save(lease, checkpoint);
        lifecycle.reserveSteps(lease, tools, modelCost, phase);
    }

    @Transactional
    public void retryRead(JobLease lease, RunCheckpointService.Prepared checkpoint, String batch,
                          List<ExecutionChange> changes,
                          String phase, ExecutionToolBinding binding, String call) {
        tools.retryRead(lease, binding, call);
        save(lease, checkpoint, batch, changes, phase, false);
    }
}

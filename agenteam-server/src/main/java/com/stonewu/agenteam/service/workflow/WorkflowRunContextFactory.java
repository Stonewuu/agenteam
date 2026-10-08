package com.stonewu.agenteam.service.workflow;

import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.agent.AgentExecutionFactory;
import com.stonewu.agenteam.service.execution.ExecutionPresentationFactory;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.execution.RunCheckpointService;
import com.stonewu.agenteam.service.execution.RunProgressService;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 两类工作流入口共用领取后的加密状态、消息保存和恢复上下文。
 */
@Component
public class WorkflowRunContextFactory {
    public record Opened(WorkflowRunContext context, boolean restored) {
    }

    private final AgentExecutionFactory agents;
    private final ExecutionPresentationFactory presentations;
    private final RunCheckpointService checkpoints;
    private final RunProgressService progress;
    private final RunApprovalService approvals;
    private final WorkflowEventMapper events;
    private final Clock clock;

    public WorkflowRunContextFactory(AgentExecutionFactory agents, ExecutionPresentationFactory presentations,
                                     RunCheckpointService checkpoints,
                                     RunProgressService progress, RunApprovalService approvals,
                                     WorkflowEventMapper events, Clock clock) {
        this.agents = agents;
        this.presentations = presentations;
        this.checkpoints = checkpoints;
        this.progress = progress;
        this.approvals = approvals;
        this.events = events;
        this.clock = clock;
    }

    public Opened open(RunRecord run, JobLease lease) {
        var states = agents.store(run);
        boolean restored = checkpoints.restore(run, states);
        var frames = presentations.open(run, lease);
        try {
            return new Opened(
                new WorkflowRunContext(run, lease, states, frames, checkpoints, progress, approvals, events, clock),
                restored);
        } catch (RuntimeException failed) {
            frames.close();
            throw failed;
        }
    }
}

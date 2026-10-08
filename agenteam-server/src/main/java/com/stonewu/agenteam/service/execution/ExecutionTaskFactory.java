package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.agent.AgentExecutionAdapter;
import com.stonewu.agenteam.service.workflow.WorkflowExecutionAdapter;
import com.stonewu.agenteam.service.workflow.WorkflowModelExecutionAdapter;
import org.springframework.stereotype.Component;

/**
 * 工作进程按本次固定的员工类型选择执行入口。
 */
@Component
public class ExecutionTaskFactory {
    private final AgentExecutionAdapter agents;
    private final WorkflowExecutionAdapter workflows;
    private final WorkflowModelExecutionAdapter modelWorkflows;

    public ExecutionTaskFactory(AgentExecutionAdapter agents, WorkflowExecutionAdapter workflows,
                                WorkflowModelExecutionAdapter modelWorkflows) {
        this.agents = agents;
        this.workflows = workflows;
        this.modelWorkflows = modelWorkflows;
    }

    public ExecutionTask create(RunRecord run, JobLease lease) {
        var config = run.executionConfig().path("config");
        if (run.executionConfig().has("workflowId") || config.path("agentType").asText().equals("workflow")) {
            return workflows.create(run, lease);
        }
        return config.path("workflowVersionIds").isEmpty() ? agents.create(run, lease) : modelWorkflows.create(run,
            lease);
    }
}

package com.stonewu.agenteam.model.workflow.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.ToolConfirmation;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.WorkflowConfirmation;

import java.util.List;

/**
 * 节点返回真实结果或具体待确认请求；等待不被当作成功结果。
 */
public record WorkflowNodeResult(JsonNode output, String branch, List<ToolConfirmation> tools,
                                 WorkflowConfirmation approval) {
    public WorkflowNodeResult {
        tools = List.copyOf(tools);
    }

    public static WorkflowNodeResult completed(JsonNode output, String branch) {
        return new WorkflowNodeResult(output, branch, List.of(), null);
    }

    public boolean waiting() {
        return !tools.isEmpty() || approval != null;
    }
}

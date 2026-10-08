package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionStepIds;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.execution.entity.ExecutionConfirmations.WorkflowConfirmation;
import com.stonewu.agenteam.model.execution.response.RunApprovalView;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.model.workflow.entity.WorkflowNodeResult;
import com.stonewu.agenteam.service.execution.RunApprovalService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 人工确认节点固定本次标题和参数；同意或拒绝只选择对应分支，不批准插件写入。
 */
@Component
public class WorkflowApprovalNodeExecutor {
    private final RunApprovalService approvals;
    private final WorkflowExpressions expressions;
    private final WorkflowValues values;
    private final ObjectMapper json;
    private final ToolPayloadMapper payloads;

    public WorkflowApprovalNodeExecutor(RunApprovalService approvals, WorkflowExpressions expressions,
                                        WorkflowValues values, ObjectMapper json, ToolPayloadMapper payloads) {
        this.approvals = approvals;
        this.expressions = expressions;
        this.values = values;
        this.json = json;
        this.payloads = payloads;
    }

    public WorkflowNodeResult execute(WorkflowRunContext context, Identity identity, WorkflowTraversal workflow,
                                      Node node, JsonNode input) {
        String title = expressions.resolve(node.config().path("title"), workflow.input(), workflow.outputs()).asText();
        String description = expressions.resolve(node.config().path("description"), workflow.input(),
            workflow.outputs()).asText();
        var secrets = new ArrayList<>(payloads.secretValues(input, List.of()));
        secrets.addAll(payloads.secretValues(workflow.input(), List.of()));
        workflow.outputs().values().forEach(output -> secrets.addAll(payloads.secretValues(output, List.of())));
        title = payloads.clean(title, secrets);
        description = payloads.clean(description, secrets);
        String content = values.write(payloads.redact(input, List.of(), secrets));
        if (title.isBlank() || title.length() > 100 || description.length() > 2000 || content.length() > 100000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKFLOW_APPROVAL_TOO_LARGE",
                "确认标题、说明或参数内容过长，请减少展示内容。");
        }
        var summary = new RunApprovalView.Summary(title, payloads.clean(identity.name(), secrets), description, content,
            null);
        String step = ExecutionStepIds.id(context.run(), "workflow-node:" + identity.id() + ":" + node.id());
        String hash = approvals.workflowHash(context.run(), step, summary);
        var decision = approvals.workflowDecision(context.run(), step, hash);
        if (decision == null) {
            return new WorkflowNodeResult(null, null, List.of(), new WorkflowConfirmation(step, hash, summary));
        }
        String branch = decision.status().equals("approved") ? "approve" : "reject";
        return WorkflowNodeResult.completed(json.createObjectNode().put("decision", branch), branch);
    }
}

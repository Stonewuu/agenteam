package com.stonewu.agenteam.model.workflow.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 输入字段及基础约束；类型之间的组合要求由对应业务代码检查。
 */
public record WorkflowConfigurationInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 40, message = "{validation.textLength}") @Pattern(regexp = "(?:Sparkles|Feather|Telescope|ShoppingBag|NotebookPen|Lightbulb|WandSparkles|BookOpen|Box|FileText|GitBranch|Library|Folders|Database|ScanText)", message = "{validation.option}") String icon,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:purple|pink|blue|amber|mint|teal|coral|indigo|plum|slate)", message = "{validation.option}") String color,
    @NotNull(message = "{validation.required}") @Size(min = 2, max = 50, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") JsonNode> nodes,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 100, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid EdgesItem> edges,
    @Size(max = 25, message = "{error.workflow.workflowConfigurationInput.groups.1}") List<@NotNull(message = "{validation.workflow.workflowConfigurationInput.groups.1}") @Valid Group> groups) {
    public record Group(
        @NotNull(message = "{error.workflow.workflowConfigurationInput.groupId.1}") @Size(min = 1, max = 64, message = "{error.workflow.workflowConfigurationInput.groupId.2}") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "{error.workflow.workflowConfigurationInput.groupId.3}") String groupId,
        @NotNull(message = "{error.workflow.workflowConfigurationInput.name.1}") @Size(min = 1, max = 50, message = "{error.workflow.workflowConfigurationInput.name.2}") String name,
        @NotNull(message = "{error.workflow.workflowConfigurationInput.nodeIds.1}") @Size(min = 2, max = 50, message = "{error.workflow.workflowConfigurationInput.nodeIds.2}") List<@NotNull(message = "{validation.workflow.workflowConfigurationInput.nodeIds.1}") @Size(min = 1, max = 64, message = "{error.workflow.workflowLoopNodeInput.nodeId.2}") String> nodeIds) {
    }

    public record EdgesItem(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") String edgeId,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") String source,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") String target,
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:default|true|false|approve|reject)", message = "{validation.option}") String branch) {
    }
}

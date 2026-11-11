package com.stonewu.agenteam.model.workflow.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 循环只执行明确的步骤区域，次数与嵌套规模均受限制。 */
public record WorkflowLoopNodeInput(
    @NotNull(message = "{error.workflow.workflowLoopNodeInput.nodeId.1}") @Size(min = 1, max = 64, message = "{error.workflow.workflowLoopNodeInput.nodeId.2}") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "{error.workflow.workflowLoopNodeInput.nodeId.3}") String nodeId,
    @NotNull(message = "{error.workflow.workflowLoopNodeInput.name.1}") @Size(min = 1, max = 50, message = "{error.workflow.workflowLoopNodeInput.name.2}") String name,
    @NotNull(message = "{error.workflow.workflowLoopNodeInput.position.1}") @Valid WorkflowParallelNodeInput.Position position,
    @NotNull(message = "{error.workflow.workflowLoopNodeInput.timeoutSeconds.1}") @Min(value = 1, message = "{error.workflow.workflowLoopNodeInput.timeoutSeconds.2}") @Max(value = 1800, message = "{error.workflow.workflowLoopNodeInput.timeoutSeconds.3}") Integer timeoutSeconds,
    @NotNull(message = "{error.workflow.workflowLoopNodeInput.failurePolicy.1}") @Pattern(regexp = "stop", message = "{error.workflow.workflowLoopNodeInput.failurePolicy.2}") String failurePolicy,
    @NotNull(message = "{error.workflow.workflowLoopNodeInput.type.1}") @Pattern(regexp = "loop", message = "{error.workflow.workflowLoopNodeInput.type.2}") String type,
    @NotNull(message = "{error.workflow.workflowLoopNodeInput.config.1}") @Valid Config config) {
    public record Config(
        @NotNull(message = "{error.workflow.workflowLoopNodeInput.mode.1}") @Pattern(regexp = "count|items", message = "{error.workflow.workflowLoopNodeInput.mode.2}") String mode,
        @Min(value = 1, message = "{error.workflow.workflowLoopNodeInput.count.1}") @Max(value = 20, message = "{error.workflow.workflowLoopNodeInput.count.2}") Integer count,
        JsonNode items,
        @NotNull(message = "{error.workflow.workflowLoopNodeInput.maxIterations.1}") @Min(value = 1, message = "{error.workflow.workflowLoopNodeInput.maxIterations.2}") @Max(value = 20, message = "{error.workflow.workflowLoopNodeInput.maxIterations.3}") Integer maxIterations,
        @NotNull(message = "{error.workflow.workflowLoopNodeInput.body.1}") JsonNode body) {
    }
}

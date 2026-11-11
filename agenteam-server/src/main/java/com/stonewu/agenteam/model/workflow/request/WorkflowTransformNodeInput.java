package com.stonewu.agenteam.model.workflow.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 工作流转换节点的输入字段约束。
 */
public record WorkflowTransformNodeInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "{validation.option}") String nodeId,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 50, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Valid Position position,
    @NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 1800L, message = "{validation.range}") Integer timeoutSeconds,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:stop|continue)", message = "{validation.option}") String failurePolicy,
    @NotNull(message = "{validation.required}") @Pattern(regexp = "transform", message = "{validation.option}") String type,
    @NotNull(message = "{validation.required}") @Valid Config config) {
    public record Position(
        @NotNull(message = "{validation.required}") Double x,
        @NotNull(message = "{validation.required}") Double y) {
    }

    public record FieldsItem(
        @NotNull(message = "{validation.required}") @Size(min = 1, max = 128, message = "{validation.textLength}") String target,
        @Size(min = 1, max = 200, message = "{validation.textLength}") String source,
        @Size(min = 0, max = 20000, message = "{validation.textLength}") String template,
        Object literal) {
    }

    public record Config(
        @NotNull(message = "{validation.required}") @Size(min = 0, max = 100, message = "{error.agent.agentConfigurationInput.suggestedQuestions.1}") List<@NotNull(message = "{validation.required}") @Valid FieldsItem> fields) {
    }
}

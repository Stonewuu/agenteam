package com.stonewu.agenteam.model.workflow.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * 工作流技能节点的输入字段约束。
 */
public record WorkflowSkillNodeInput(
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 64, message = "{validation.textLength}") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "{validation.option}") String nodeId,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 50, message = "{validation.textLength}") String name,
    @NotNull(message = "{validation.required}") @Valid Position position,
    @NotNull(message = "{validation.required}") @Min(value = 1L, message = "{validation.range}") @Max(value = 1800L, message = "{validation.range}") Integer timeoutSeconds,
    @NotNull(message = "{validation.required}") @Size(min = 1, max = 20, message = "{validation.textLength}") @Pattern(regexp = "(?:stop|continue)", message = "{validation.option}") String failurePolicy,
    @NotNull(message = "{validation.required}") @Pattern(regexp = "skill", message = "{validation.option}") String type,
    @NotNull(message = "{validation.required}") @Valid Config config) {
    public record Position(
        @NotNull(message = "{validation.required}") Double x,
        @NotNull(message = "{validation.required}") Double y) {
    }

    public record Config(
        @JsonProperty(value = "agentVersionId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String agentVersionId,
        @JsonProperty(value = "skillVersionId", required = true) @Size(min = 1, max = 100, message = "{validation.textLength}") String skillVersionId,
        @NotNull(message = "{validation.required}") Map<String, Object> inputMapping) {
    }
}

package com.stonewu.agenteam.model.workflow.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * 工作流智能体节点的输入字段约束。
 */
public record WorkflowAgentNodeInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "请选择支持的选项或格式。") String nodeId,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 50, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Valid Position position,
    @NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 1800L, message = "数值超出允许范围。") Integer timeoutSeconds,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:stop|continue)", message = "请选择支持的选项或格式。") String failurePolicy,
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "agent", message = "请选择支持的选项或格式。") String type,
    @NotNull(message = "请填写必填项。") @Valid Config config) {
    public record Position(
        @NotNull(message = "请填写必填项。") Double x,
        @NotNull(message = "请填写必填项。") Double y) {
    }

    public record Config(
        @JsonProperty(value = "agentVersionId", required = true) @Size(min = 1, max = 100, message = "文字长度不符合要求。") String agentVersionId,
        @NotNull(message = "请填写必填项。") Map<String, Object> inputMapping) {
    }
}

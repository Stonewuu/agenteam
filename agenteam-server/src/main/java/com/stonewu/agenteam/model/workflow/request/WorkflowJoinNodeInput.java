package com.stonewu.agenteam.model.workflow.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


/**
 * 工作流汇合节点的输入字段约束。
 */
public record WorkflowJoinNodeInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "请选择支持的选项或格式。") String nodeId,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 50, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Valid Position position,
    @NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 1800L, message = "数值超出允许范围。") Integer timeoutSeconds,
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "stop", message = "请选择支持的选项或格式。") String failurePolicy,
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "join", message = "请选择支持的选项或格式。") String type,
    @NotNull(message = "请填写必填项。") @Valid Config config) {
    public record Position(
        @NotNull(message = "请填写必填项。") Double x,
        @NotNull(message = "请填写必填项。") Double y) {
    }

    public record Config(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") String parallelNodeId) {
    }
}

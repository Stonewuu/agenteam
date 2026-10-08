package com.stonewu.agenteam.model.workflow.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 工作流转换节点的输入字段约束。
 */
public record WorkflowTransformNodeInput(
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 64, message = "文字长度不符合要求。") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "请选择支持的选项或格式。") String nodeId,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 50, message = "文字长度不符合要求。") String name,
    @NotNull(message = "请填写必填项。") @Valid Position position,
    @NotNull(message = "请填写必填项。") @Min(value = 1L, message = "数值超出允许范围。") @Max(value = 1800L, message = "数值超出允许范围。") Integer timeoutSeconds,
    @NotNull(message = "请填写必填项。") @Size(min = 1, max = 20, message = "文字长度不符合要求。") @Pattern(regexp = "(?:stop|continue)", message = "请选择支持的选项或格式。") String failurePolicy,
    @NotNull(message = "请填写必填项。") @Pattern(regexp = "transform", message = "请选择支持的选项或格式。") String type,
    @NotNull(message = "请填写必填项。") @Valid Config config) {
    public record Position(
        @NotNull(message = "请填写必填项。") Double x,
        @NotNull(message = "请填写必填项。") Double y) {
    }

    public record FieldsItem(
        @NotNull(message = "请填写必填项。") @Size(min = 1, max = 128, message = "文字长度不符合要求。") String target,
        @Size(min = 1, max = 200, message = "文字长度不符合要求。") String source,
        @Size(min = 0, max = 20000, message = "文字长度不符合要求。") String template,
        Object literal) {
    }

    public record Config(
        @NotNull(message = "请填写必填项。") @Size(min = 0, max = 100, message = "条目数量超出允许范围。") List<@NotNull(message = "请填写必填项。") @Valid FieldsItem> fields) {
    }
}

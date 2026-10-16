package com.stonewu.agenteam.model.workflow.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 循环只执行明确的步骤区域，次数与嵌套规模均受限制。 */
public record WorkflowLoopNodeInput(
    @NotNull(message = "请填写节点编号。") @Size(min = 1, max = 64, message = "节点编号长度不符合要求。") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "节点编号格式不正确。") String nodeId,
    @NotNull(message = "请填写节点名称。") @Size(min = 1, max = 50, message = "节点名称需要一至五十个字符。") String name,
    @NotNull(message = "请填写节点位置。") @Valid WorkflowParallelNodeInput.Position position,
    @NotNull(message = "请填写循环总处理时间。") @Min(value = 1, message = "处理时间至少为一秒。") @Max(value = 1800, message = "处理时间最多为一千八百秒。") Integer timeoutSeconds,
    @NotNull(message = "请选择失败后的处理方式。") @Pattern(regexp = "stop", message = "循环失败时必须停止。") String failurePolicy,
    @NotNull(message = "请选择节点类型。") @Pattern(regexp = "loop", message = "请选择循环节点。") String type,
    @NotNull(message = "请填写循环配置。") @Valid Config config) {
    public record Config(
        @NotNull(message = "请选择循环方式。") @Pattern(regexp = "count|items", message = "请选择按次数或逐项处理。") String mode,
        @Min(value = 1, message = "执行次数至少为一次。") @Max(value = 20, message = "执行次数最多为二十次。") Integer count,
        JsonNode items,
        @NotNull(message = "请填写循环上限。") @Min(value = 1, message = "循环上限至少为一次。") @Max(value = 20, message = "循环上限最多为二十次。") Integer maxIterations,
        @NotNull(message = "请配置循环内部步骤。") JsonNode body) {
    }
}

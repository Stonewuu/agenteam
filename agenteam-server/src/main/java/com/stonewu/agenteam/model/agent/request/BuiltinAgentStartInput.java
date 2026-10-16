package com.stonewu.agenteam.model.agent.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 内置助手仍由用户选择模型和提交真实任务内容。 */
public record BuiltinAgentStartInput(
    @NotBlank(message = "请选择模型。") @Size(max = 100, message = "模型编号过长。") String modelProfileId,
    @NotBlank(message = "请描述要创建或修改的内容。") @Size(max = 18000, message = "内容最多为一万八千个字符。") String message,
    @Size(min = 1, max = 100, message = "目标资源编号长度不正确。") String targetResourceId) {
}

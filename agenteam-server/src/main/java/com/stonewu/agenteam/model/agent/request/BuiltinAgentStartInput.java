package com.stonewu.agenteam.model.agent.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 内置助手仍由用户选择模型和提交真实任务内容。 */
public record BuiltinAgentStartInput(
    @NotBlank(message = "{error.agent.builtinAgentStartInput.modelProfileId.1}") @Size(max = 100, message = "{error.agent.builtinAgentStartInput.modelProfileId.2}") String modelProfileId,
    @NotBlank(message = "{error.agent.builtinAgentStartInput.message.1}") @Size(max = 18000, message = "{error.agent.builtinAgentStartInput.message.2}") String message,
    @Size(min = 1, max = 100, message = "{error.agent.builtinAgentStartInput.targetResourceId.1}") String targetResourceId) {
}

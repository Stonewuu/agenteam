package com.stonewu.agenteam.model.execution.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.modelprofile.entity.ReasoningEffort;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 对话明确选择的模型；思考等级为空时使用模型自身的默认行为。
 */
public record ModelSelection(
    @NotBlank(message = "{error.agent.builtinAgentStartInput.modelProfileId.1}") @Size(max = 100, message = "{error.agent.builtinAgentStartInput.modelProfileId.2}") String modelProfileId,
    @Pattern(regexp = ReasoningEffort.PATTERN, message = "{error.agent.agentConfigurationInput.reasoningEffort.1}") String reasoningEffort) {
    public static ModelSelection fromConfig(JsonNode config) {
        String id = config.path("modelProfileId").asText(null);
        return id == null ? null : new ModelSelection(id, config.path("reasoningEffort").asText(null));
    }
}
